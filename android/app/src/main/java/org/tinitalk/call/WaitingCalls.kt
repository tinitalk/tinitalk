package org.tinitalk.call

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import com.google.gson.JsonObject
import org.tinitalk.R
import org.tinitalk.data.AuthStore
import org.tinitalk.data.signal.ApplicationSignaling
import org.tinitalk.data.signal.SignalConnection
import org.tinitalk.data.signal.SignalEvent
import org.tinitalk.push.DeviceIdentity
import org.tinitalk.push.IncomingCallHandler
import org.tinitalk.push.IncomingInvite
import org.tinitalk.push.IncomingRingingAcknowledger
import org.tinitalk.push.MissedCountRefreshScheduler
import org.tinitalk.telecom.CallForegroundService
import org.tinitalk.telecom.IncomingCallController
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet

internal data class WaitingCallsState(val calls: List<WaitingCall> = emptyList(), val answering: AccountCallOwner? = null)

/** Application-owned signaling only. A waiting entry never allocates WebRTC or Telecom. */
internal object WaitingCalls {
    // initialize() always passes applicationContext; no Activity is retained.
    @android.annotation.SuppressLint("StaticFieldLeak")
    private var controller: WaitingCallsController? = null
    private val observers = CopyOnWriteArraySet<(WaitingCallsState) -> Unit>()
    @Volatile private var state = WaitingCallsState()
    fun initialize(context: Context, auth: AuthStore) {
        controller?.close()
        controller = WaitingCallsController(context.applicationContext, auth, ::publish)
    }
    fun close() { controller?.close(); controller = null; publish(WaitingCallsState()) }
    fun snapshot() = state
    fun observe(observer: (WaitingCallsState) -> Unit) { observers += observer; observer(state) }
    fun removeObserver(observer: (WaitingCallsState) -> Unit) { observers -= observer }
    private fun publish(next: WaitingCallsState) { state = next; observers.forEach { it(next) } }
    fun present(invite: IncomingInvite): Boolean = controller?.present(invite) == true
    fun cancel(owner: AccountCallOwner) { controller?.cancel(owner) }
    fun isAnswering(owner: AccountCallOwner): Boolean = controller?.isAnswering(owner) == true
    fun answer(owner: AccountCallOwner) { controller?.answer(owner) }
    fun reject(owner: AccountCallOwner) { controller?.reject(owner) }
    fun mediaRetiring(owner: AccountCallOwner) { controller?.mediaRetiring(owner) }
    fun mediaReleased(owner: AccountCallOwner) { controller?.mediaReleased(owner) }
}

internal class WaitingCallsController(
    private val context: Context,
    private val auth: AuthStore,
    private val publish: (WaitingCallsState) -> Unit,
    private val acquire: (org.tinitalk.data.Session, String) -> SignalConnection = ApplicationSignaling::acquire,
    private val presentation: WaitingCallPresentation = WaitingCallPresentation(context),
) : AutoCloseable {
    private val handler = Handler(Looper.getMainLooper())
    private val registry = WaitingCallRegistry()
    private val sockets = mutableMapOf<AccountCallOwner, SignalConnection>()
    private val acceptRequests = mutableMapOf<AccountCallOwner, String>()
    private val retiring = mutableSetOf<AccountCallOwner>()
    private val promotions = mutableSetOf<AccountCallOwner>()
    private var accepted = false
    private var acceptSent = false
    private var endRequested = false
    private var selectedAt = 0L
    private var closed = false
    private val observer: (CallUiState) -> Unit = { handler.post { refresh() } }
    private val tick = object : Runnable {
        override fun run() {
            if (closed) return
            val now = SystemClock.elapsedRealtime()
            registry.snapshot().filterNot { isCurrent(it.invite.owner) }.forEach { remove(it.invite.owner) }
            registry.expired(now).forEach { reject(it.invite.owner, seen = false) }
            val selected = registry.currentSelection()
            if (selected != null) {
                if (now - selectedAt >= 10_000) failSelection(selected)
                else advance(selected)
            } else promoteWhenIdle()
            refresh()
            if (registry.snapshot().isNotEmpty()) handler.postDelayed(this, 200)
        }
    }
    init { CallUiStateStore.observe(observer) }

    private fun isCurrent(owner: AccountCallOwner): Boolean {
        val binding = owner.sessionBinding
        return auth.matchesSessionIdentity(owner.key.accountId, binding.serverUrl, binding.login, binding.sessionId, binding.configId)
    }

    fun present(invite: IncomingInvite): Boolean {
        if (closed || invite.serverAccepted) return false
        if (registry.get(invite.owner) != null) return true
        val active = CallUiStateStore.snapshot()
        if (!invite.waitingSupported || active.phase != CallPhase.Active || active.connectedAtElapsedMs == null ||
            active.callKey == invite.key) return false
        handler.post {
            if (closed || registry.get(invite.owner) != null || !isCurrent(invite.owner)) return@post
            val incoming = IncomingCallController()
            if (incoming.isTerminal(context, invite.owner)) return@post
            if (CallUiStateStore.snapshot().phase != CallPhase.Active) {
                IncomingCallHandler(context).present(invite)
                return@post
            }
            val remaining = Duration.between(Instant.now(), invite.expiresAt).toMillis()
            if (!registry.add(invite, SystemClock.elapsedRealtime(), remaining)) {
                IncomingRingingAcknowledger(context).rejectBusy(invite)
                return@post
            }
            val session = resolvePinnedCallSession(auth, invite.accountId, invite.sessionBinding)
            if (session == null) { remove(invite.owner); return@post }
            val socket = acquire(session, DeviceIdentity.id(context))
            sockets[invite.owner] = socket
            socket.connect(
                onEvent = { event -> handler.post { receive(invite.owner, event) } },
                onOpen = { handler.post {
                    if (sockets[invite.owner] !== socket) return@post
                    val pending = registry.get(invite.owner) ?: return@post
                    send(invite.owner, "call.resume", JsonObject().apply { addProperty("last_seq", pending.lastSeq) })
                    if (!pending.acknowledged) sendWaiting(invite.owner, true)
                } },
                onError = { failure -> handler.post {
                    if (sockets[invite.owner] !== socket) return@post
                    if (failure.callId == invite.callId) {
                        val selected = registry.currentSelection()?.takeIf { it.owner == invite.owner }
                        if (selected != null) failSelection(selected) else remove(invite.owner)
                    }
                } },
            )
            handler.removeCallbacks(tick)
            handler.post(tick)
            refresh()
        }
        return true
    }

    private fun receive(owner: AccountCallOwner, event: SequencedSignalEvent) {
        if (closed) return
        val pending = registry.get(owner) ?: return
        if (!isCurrent(owner) || event.event.callId != owner.key.callId || event.seq <= pending.lastSeq) return
        when (event.event.type) {
            "call.waiting" -> {
                val payload = event.event.payload
                val waiting = payload["waiting"]?.asBoolean ?: return
                val remaining = payload["remaining_ms"]?.asLong ?: return
                registry.acknowledge(owner, event.seq, remaining, SystemClock.elapsedRealtime(), waiting)
                promotions.remove(owner)
            }
            "call.cancel", "call.reject", "call.expire", "call.end" -> {
                registry.removeTerminal(owner, event.seq) ?: return
                remove(owner, terminal = true)
                MissedCountRefreshScheduler(context).enqueue(owner.key.accountId)
            }
            // The accept ACK can be lost. rtc.config proves this invitation was accepted.
            "rtc.config" -> if (registry.currentSelection()?.owner == owner) {
                accepted = true
                registry.currentSelection()?.let(::advance)
            }
        }
        refresh()
    }

    fun answer(owner: AccountCallOwner) { handler.post {
        val previous = GlobalCallAdmission.current()?.owner
        val selected = registry.select(owner, previous, SystemClock.elapsedRealtime()) ?: return@post
        selectedAt = SystemClock.elapsedRealtime()
        accepted = false
        acceptSent = false
        endRequested = false
        val active = CallUiStateStore.snapshot()
        if (previous != null && previous.key.accountId == owner.key.accountId && active.phase == CallPhase.Active) {
            requestAccept(selected, previous.key.callId)
        } else {
            // Servers cannot atomically switch a call owned by another server.
            if (previous != null) {
                endRequested = true
                val pending = IncomingCallController().load(context)?.invite
                if (active.phase == CallPhase.Ringing && pending?.owner == previous) IncomingCallController().reject(context, pending)
                else CallForegroundService.end(context)
            }
            advance(selected)
        }
        refresh()
    } }

    private fun requestAccept(selected: WaitingCallSelection, replaceId: String? = null) {
        if (acceptSent || !registry.isSelected(selected)) return
        val pending = registry.get(selected.owner) ?: return
        if (pending.deadlineElapsedMs <= SystemClock.elapsedRealtime()) { failSelection(selected); return }
        acceptSent = true
        val payload = JsonObject().apply {
            addProperty("supports_video", true)
            addProperty("supports_exclusive_screen_sharing", true)
            addProperty("supports_call_sas", true)
            replaceId?.let { addProperty("replace_call_id", it) }
        }
        val request = event(selected.owner, "call.accept", payload)
        acceptRequests[selected.owner] = request.id
        sockets[selected.owner]?.sendTracked(request) { result -> handler.post {
            if (!registry.isSelected(selected)) return@post
            when (result) {
                SignalSendResult.Acknowledged -> { accepted = true; advance(selected) }
                SignalSendResult.Rejected -> failSelection(selected)
                SignalSendResult.Unconfirmed -> send(selected.owner, "call.resume", JsonObject().apply {
                    addProperty("last_seq", pending.lastSeq)
                })
            }
        } }
    }

    private fun advance(selected: WaitingCallSelection) {
        if (!registry.isSelected(selected)) return
        val current = GlobalCallAdmission.current()?.owner
        if (current != null) {
            if (accepted && current == selected.previous && !endRequested) {
                endRequested = true
                CallForegroundService.remoteEnded(context, current)
            }
            return
        }
        if (retiring.isNotEmpty()) return // Await actual WebRTC disposal, not just its hidden UI.
        if (!acceptSent) { requestAccept(selected); return }
        if (!accepted) return
        val pending = registry.get(selected.owner) ?: return
        val invite = pending.invite.copy(
            serverAccepted = true,
            // This is an already accepted call, not a new 45-second invitation.
            expiresAt = Instant.now().plusSeconds(10),
        )
        try {
            IncomingCallHandler(context).present(invite)
            if (GlobalCallAdmission.current()?.owner != invite.owner) {
                failSelection(selected)
                return
            }
            remove(selected.owner)
        } catch (_: Exception) {
            IncomingCallController().clear(context, invite.owner)
            failSelection(selected)
        }
    }

    private fun failSelection(selected: WaitingCallSelection) {
        if (!registry.isSelected(selected)) return
        // Acceptance may have reached the server even if its ACK did not reach us.
        settleTerminal(selected.owner, if (acceptSent) "call.end" else "call.reject")
        Toast.makeText(context, R.string.waiting_call_unavailable, Toast.LENGTH_SHORT).show()
    }

    fun reject(owner: AccountCallOwner, seen: Boolean = true) { handler.post {
        if (registry.get(owner) == null || registry.currentSelection()?.owner == owner) return@post
        settleTerminal(owner, "call.reject", seen = seen)
        MissedCountRefreshScheduler(context).enqueue(owner.key.accountId)
    } }

    private fun settleTerminal(owner: AccountCallOwner, type: String, seen: Boolean = false) {
        val socket = sockets[owner]
        acceptRequests.remove(owner)?.let { socket?.cancelQueued(it) }
        val request = event(owner, type, JsonObject().apply {
            addProperty("reason", "busy")
            if (seen) addProperty("seen", true)
        })
        // Retain the signaling lease until its terminal packet is settled.
        sockets.remove(owner)
        remove(owner, terminal = true)
        socket?.sendTracked(request) { handler.post { socket.close() } }
        if (socket != null) handler.postDelayed({ socket.close() }, 5_000)
    }
    fun isAnswering(owner: AccountCallOwner): Boolean = registry.currentSelection()?.owner == owner
    fun cancel(owner: AccountCallOwner) { handler.post {
        if (registry.get(owner) != null) MissedCountRefreshScheduler(context).enqueue(owner.key.accountId)
        remove(owner, terminal = true)
    } }
    fun mediaRetiring(owner: AccountCallOwner) { retiring += owner }
    fun mediaReleased(owner: AccountCallOwner) { handler.post {
        retiring -= owner
        registry.currentSelection()?.let(::advance) ?: promoteWhenIdle()
        refresh()
    } }

    private fun promoteWhenIdle() {
        if (retiring.isNotEmpty() || GlobalCallAdmission.current() != null || registry.currentSelection() != null) return
        val pending = registry.snapshot()
        pending.filter { it.waiting && it.acknowledged && it.invite.owner !in promotions }.forEach {
            promotions += it.invite.owner
            sendWaiting(it.invite.owner, false)
        }
        // One ordinary ringing presentation, while all remaining invitations stay selectable.
        pending.firstOrNull { !it.waiting && it.deadlineElapsedMs > SystemClock.elapsedRealtime() }?.let {
            remove(it.invite.owner)
            IncomingCallHandler(context).present(it.invite.copy(expiresAt = Instant.now().plusMillis(
                it.deadlineElapsedMs - SystemClock.elapsedRealtime(),
            )))
        }
    }
    private fun sendWaiting(owner: AccountCallOwner, waiting: Boolean) = send(owner, "call.waiting", JsonObject().apply { addProperty("waiting", waiting) })
    private fun event(owner: AccountCallOwner, type: String, payload: JsonObject) = SignalEvent(
        UUID.randomUUID().toString(), owner.key.callId, type, System.currentTimeMillis(), payload,
    )
    private fun send(owner: AccountCallOwner, type: String, payload: JsonObject = JsonObject()) {
        sockets[owner]?.send(event(owner, type, payload))
    }
    private fun remove(owner: AccountCallOwner, terminal: Boolean = false) {
        acceptRequests.remove(owner)?.let { sockets[owner]?.cancelQueued(it) }
        registry.remove(owner)
        sockets.remove(owner)?.close()
        promotions.remove(owner)
        if (terminal) IncomingCallController().rememberTerminal(context, owner)
        refresh()
    }
    private fun refresh() {
        if (closed) return
        val next = WaitingCallsState(registry.snapshot(), registry.currentSelection()?.owner)
        val active = CallUiStateStore.snapshot()
        if (active.phase == CallPhase.Active && active.connectedAtElapsedMs != null) {
            next.calls.filter { !it.waiting && it.invite.owner !in promotions }.forEach {
                promotions += it.invite.owner
                sendWaiting(it.invite.owner, true)
            }
        }
        publish(next)
        presentation.render(next, active)
    }
    override fun close() {
        closed = true
        handler.removeCallbacksAndMessages(null)
        CallUiStateStore.removeObserver(observer)
        sockets.values.forEach { it.close() }
        sockets.clear()
        presentation.close()
    }
}
