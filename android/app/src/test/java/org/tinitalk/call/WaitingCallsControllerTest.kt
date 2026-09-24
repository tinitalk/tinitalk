package org.tinitalk.call

import android.os.Looper
import com.google.gson.JsonObject
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.tinitalk.data.*
import org.tinitalk.data.signal.*
import org.tinitalk.media.MediaConnectionState
import org.tinitalk.push.IncomingInvite
import org.tinitalk.push.ContactPhotoNotificationLoader
import java.time.Duration
import java.time.Instant
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35], application = org.tinitalk.i18n.LocalizedTestApplication::class)
class WaitingCallsControllerTest {
    private val app = RuntimeEnvironment.getApplication()
    private val auth = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher())
    private val account = auth.upsert(Session("https://one.example", "me", "token", sessionId = "s1"))
    private val other = auth.upsert(Session("https://two.example", "me", "token", sessionId = "s2"))
    private val previous = AccountCallOwner(AccountCallKey(account.id, UUID.randomUUID().toString()), CallSessionBinding.from(account.session))
    private val lease = (GlobalCallAdmission.stage(previous) as CallAdmissionAttempt.Acquired).lease
    private val sockets = mutableListOf<Socket>()
    private var state = WaitingCallsState()
    private val photoLoader = ContactPhotoNotificationLoader(object : ContactPhotoReader {
        override val revision = MutableStateFlow(0L)
        override fun peekBitmap(address: ContactAddress, targetPixels: Int) = null
        override fun loadBitmap(address: ContactAddress, targetPixels: Int) = null
    }) { it.run() }
    private val controller = WaitingCallsController(app, auth, { state = it }, { _, _ -> Socket().also(sockets::add) },
        presentation = WaitingCallPresentation(app, photoLoader))

    init {
        GlobalCallAdmission.take(previous)
        CallUiStateStore.begin(previous.key, CallPeer("First"), CallDirection.Outgoing, CallPhase.Active)
        CallUiStateStore.onMediaConnection(MediaConnectionState.Connected)
        idle()
    }

    @After fun cleanup() {
        controller.close()
        GlobalCallAdmission.release(lease)
        GlobalCallAdmission.current()?.owner?.let { org.tinitalk.telecom.IncomingCallController().clear(app, it) }
        CallUiStateStore.reset()
        idle()
    }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun invite(account: AccountRecord = this.account) = IncomingInvite(account.id,
        CallSessionBinding.from(account.session), UUID.randomUUID().toString(), "Second", Instant.now().plusSeconds(45),
        "second", 1, Instant.now(), waitingSupported = true)
    private fun admit(invite: IncomingInvite): Socket {
        assertTrue(controller.present(invite))
        idle()
        return sockets.last().also { socket ->
            socket.receive(invite, "call.waiting", 2, JsonObject().apply { addProperty("waiting", true); addProperty("remaining_ms", 15_000) })
            idle()
        }
    }

    @Test fun repeatedInviteIsAcknowledgedOnceAndExpiresWithoutEndingConversation() {
        val invite = invite()
        val socket = admit(invite)
        assertTrue(controller.present(invite))
        idle()
        assertEquals(1, sockets.size)
        assertEquals(1, socket.events.count { it.type == "call.waiting" })
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(16))
        assertTrue(state.calls.isEmpty())
        assertTrue(socket.events.any { it.type == "call.reject" && it.payload["reason"].asString == "busy" })
        assertFalse(socket.events.last { it.type == "call.reject" }.payload.has("seen"))
        assertEquals(previous, GlobalCallAdmission.current()!!.owner)
        assertEquals(CallPhase.Active, CallUiStateStore.snapshot().phase)
    }

    @Test fun oldServerInviteDoesNotEnterWaitingOrSendUnsupportedCommands() {
        assertFalse(controller.present(invite(other).copy(waitingSupported = false)))
        idle()
        assertTrue(sockets.isEmpty())
        assertTrue(state.calls.isEmpty())
        assertEquals(previous, GlobalCallAdmission.current()!!.owner)
        assertEquals(CallPhase.Active, CallUiStateStore.snapshot().phase)
    }

    @Test fun manualRejectionMarksOnlyThatInvitationSeen() {
        val first = invite()
        val second = invite(other)
        val firstSocket = admit(first)
        val secondSocket = admit(second)
        controller.reject(first.owner)
        idle()
        val rejection = firstSocket.events.last { it.type == "call.reject" }
        assertEquals("busy", rejection.payload["reason"].asString)
        assertTrue(rejection.payload["seen"].asBoolean)
        assertFalse(secondSocket.events.any { it.type == "call.reject" })
        assertEquals(listOf(second.owner), state.calls.map { it.invite.owner })
        assertEquals(previous, GlobalCallAdmission.current()!!.owner)
    }

    @Test fun replyRejectsOnlySelectedCallerWithoutBusyOrEndingConversation() {
        val first = invite()
        val second = invite(other)
        val socket = admit(first)
        val secondSocket = admit(second)
        controller.reply(first.owner, CallReplyCode.WillCallBack)
        idle()
        val rejection = socket.events.single { it.type == "call.reject" }
        assertEquals("will_call_back", rejection.payload["reply_code"].asString)
        assertFalse(rejection.payload.has("reason"))
        assertFalse(secondSocket.events.any { it.type == "call.reject" })
        assertEquals(listOf(second.owner), state.calls.map { it.invite.owner })
        assertEquals(previous, GlobalCallAdmission.current()!!.owner)
        controller.reply(first.owner, CallReplyCode.CallMeLater)
        idle()
        assertEquals(1, socket.events.count { it.type == "call.reject" })
    }

    @Test fun replyAfterCancellationDoesNothing() {
        val invite = invite()
        val socket = admit(invite)
        socket.receive(invite, "call.cancel", 3)
        idle()
        controller.reply(invite.owner, CallReplyCode.WillCallBack)
        idle()
        assertFalse(socket.events.any { it.type == "call.reject" })
        assertEquals(previous, GlobalCallAdmission.current()!!.owner)
    }

    @Test fun replyAfterTimeoutDoesNotSendText() {
        val invite = invite()
        val socket = admit(invite)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(16))
        controller.reply(invite.owner, CallReplyCode.WillCallBack)
        idle()
        val rejection = socket.events.single { it.type == "call.reject" }
        assertFalse(rejection.payload.has("reply_code"))
        assertFalse(rejection.payload.has("seen"))
    }

    @Test fun rejectedSameServerSelectionNeverStopsCurrentConversation() {
        val invite = invite()
        val socket = admit(invite)
        controller.answer(invite.owner)
        idle()
        assertEquals(previous.key.callId, socket.events.last { it.type == "call.accept" }.payload["replace_call_id"].asString)
        socket.result!!(SignalSendResult.Rejected)
        idle()
        assertTrue(state.calls.isEmpty())
        assertEquals(previous, GlobalCallAdmission.current()!!.owner)
        assertEquals(CallPhase.Active, CallUiStateStore.snapshot().phase)
    }

    @Test fun cancellationInvalidatesSelectionBeforeLateAcceptAcknowledgement() {
        val invite = invite()
        val socket = admit(invite)
        controller.answer(invite.owner)
        idle()
        socket.receive(invite, "call.cancel", 3)
        idle()
        socket.result!!(SignalSendResult.Acknowledged)
        idle()
        assertNull(state.answering)
        assertTrue(state.calls.isEmpty())
        assertEquals(previous, GlobalCallAdmission.current()!!.owner)
    }

    @Test fun differentServerIsNotAcceptedUntilOldMediaIsDisposed() {
        val invite = invite(other)
        val socket = admit(invite)
        controller.answer(invite.owner)
        idle()
        assertFalse(socket.events.any { it.type == "call.accept" })
        controller.mediaRetiring(previous)
        GlobalCallAdmission.release(lease)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertFalse(socket.events.any { it.type == "call.accept" })
        controller.mediaReleased(previous)
        idle()
        val accept = socket.events.single { it.type == "call.accept" }
        assertFalse(accept.payload.has("replace_call_id"))
    }

    private class Socket : SignalConnection {
        val events = mutableListOf<SignalEvent>()
        var result: ((SignalSendResult) -> Unit)? = null
        private var listener: ((SequencedSignalEvent) -> Unit)? = null
        override fun connect(onEvent: (SequencedSignalEvent) -> Unit, onOpen: (Long) -> Unit,
                             onDisconnected: (Long) -> Unit, onError: (SignalFailure) -> Unit) {
            listener = onEvent; onOpen(1)
        }
        fun receive(invite: IncomingInvite, type: String, seq: Long, payload: JsonObject = JsonObject()) {
            listener!!(SequencedSignalEvent(SignalEvent(UUID.randomUUID().toString(), invite.callId, type, System.currentTimeMillis(), payload), seq))
        }
        override fun send(event: SignalEvent, onSettled: (() -> Unit)?) { events += event; onSettled?.invoke() }
        override fun sendTracked(event: SignalEvent, onResult: (SignalSendResult) -> Unit) { events += event; result = onResult }
        override fun isOpen() = true
        override fun isOpen(expectedGeneration: Long) = true
        override fun sendVisibility(callId: String, visible: Boolean) = true
        override fun reconnectNow() = Unit
        override fun close() = Unit
    }
}
