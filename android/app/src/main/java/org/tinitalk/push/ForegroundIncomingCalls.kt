package org.tinitalk.push

import android.app.Activity
import android.app.Application
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.google.gson.JsonParser
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.tinitalk.call.AccountCallKey
import org.tinitalk.call.CallAdmissionState
import org.tinitalk.call.CallSessionBinding
import org.tinitalk.call.GlobalCallAdmission
import org.tinitalk.data.AccountCollectionKey
import org.tinitalk.data.AccountId
import org.tinitalk.data.AccountRecord
import org.tinitalk.data.AuthStore
import org.tinitalk.data.ContactCache
import org.tinitalk.data.Session
import org.tinitalk.data.SessionIdHeader
import org.tinitalk.data.SessionReplacedReason
import org.tinitalk.data.SharedPreferencesKeyValueStore
import org.tinitalk.data.signal.ApplicationSignaling
import org.tinitalk.data.signal.SignalConnection
import org.tinitalk.media.DefaultNetworkObserver
import org.tinitalk.telecom.IncomingCallController
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.UUID

/** Only foreground UI owns these subscriptions. Call services hold independent leases. */
internal class ForegroundIncomingCalls(
    private val application: Application,
    private val authStore: AuthStore,
    private val networkAvailable: () -> Boolean,
    private val acquire: (Session, String) -> SignalConnection = ApplicationSignaling::acquire,
    private val present: (IncomingInvite) -> Unit = IncomingCallHandler(application)::present,
    private val cancel: (AccountRecord, CallCancellation) -> Unit = IncomingCallHandler(application)::cancel,
) : Application.ActivityLifecycleCallbacks, AutoCloseable {
    private val handler = Handler(Looper.getMainLooper())
    private val preferences = application.getSharedPreferences("auth", Context.MODE_PRIVATE)
    private val accountReader = Executors.newSingleThreadExecutor { task ->
        Thread(task, "tinitalk-incoming-accounts").apply { isDaemon = true }
    }
    private val http = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()
    private val cache = ContactCache(SharedPreferencesKeyValueStore(application))
    private val resumed = mutableSetOf<Activity>()
    private val listeners = mutableMapOf<AccountId, Listener>()
    private var networkObserver: DefaultNetworkObserver? = null
    private var screenReceiverRegistered = false
    private var revision = 0L
    private var closed = false
    private val reconcile = Runnable { refresh() }
    private val visibility = Runnable { updateVisibility() }
    private var hideScheduled = false
    private val hideVisibility = Runnable {
        hideScheduled = false
        updateVisibility(withdrawHidden = true)
    }
    private val screenObserver: () -> Unit = { handler.post(visibility) }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refresh()
            updateVisibility()
        }
    }
    private val accountsObserver = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == AccountCollectionKey) handler.post(reconcile)
    }

    init {
        application.registerActivityLifecycleCallbacks(this)
        preferences.registerOnSharedPreferenceChangeListener(accountsObserver)
        IncomingCallScreenState.observe(screenObserver)
    }

    private fun canListen(): Boolean = !closed && resumed.isNotEmpty() && networkAvailable() &&
        application.getSystemService(PowerManager::class.java)?.isInteractive != false &&
        application.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != true

    fun networkChanged() { handler.post(reconcile) }

    override fun onActivityResumed(activity: Activity) {
        // A process woken only by push has no foreground listener to maintain.
        if (!screenReceiverRegistered) {
            ContextCompat.registerReceiver(application, screenReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }, ContextCompat.RECEIVER_NOT_EXPORTED)
            screenReceiverRegistered = true
        }
        resumed += activity
        handler.removeCallbacks(reconcile)
        refresh()
        updateVisibility()
    }

    override fun onActivityPaused(activity: Activity) {
        resumed -= activity
        revision++
        updateVisibility()
        // Moving between our activities should not churn TCP/TLS connections.
        handler.removeCallbacks(reconcile)
        handler.postDelayed(reconcile, 500)
    }

    private fun refresh() {
        val requested = ++revision
        if (!canListen()) {
            listeners.values.toList().forEach(Listener::close)
            listeners.clear()
            networkObserver?.close()
            networkObserver = null
            return
        }
        if (networkObserver == null) networkObserver = DefaultNetworkObserver(application) {
            handler.post {
                if (!canListen()) return@post
                val running = GlobalCallAdmission.current()?.takeIf { it.state == CallAdmissionState.Running }
                listeners.values.filter { it.account.id != running?.owner?.key?.accountId }
                    .forEach { it.socket.reconnectNow() }
                refresh()
            }
        }
        accountReader.execute {
            val accounts = runCatching { authStore.list() }.getOrNull() ?: return@execute
            handler.post {
                if (requested != revision || !canListen()) return@post
                val desired = accounts.associateBy { it.id }
                listeners.values.filter { current ->
                    desired[current.account.id]?.let(current::matches) != true
                }.toList().forEach { old -> listeners.remove(old.account.id); old.close() }
                accounts.forEach { account ->
                    if (account.id !in listeners) {
                        val listener = Listener(account)
                        listeners[account.id] = listener
                        listener.start()
                    }
                }
            }
        }
    }

    private inner class Listener(val account: AccountRecord) : AutoCloseable {
        val socket = acquire(account.session, DeviceIdentity.id(application))
        private var recovery: Call? = null
        var visibleCall: String? = null

        fun matches(other: AccountRecord): Boolean = account.id == other.id &&
            CallSessionBinding.from(account.session).matches(other.session) && account.session.token == other.session.token

        private fun current(): Boolean = !closed && listeners[account.id] === this

        // No Keystore/decryption on the UI thread. Session identity changes on a new login.
        private fun currentSession(): Boolean = current() && runCatching {
            val session = account.session
            authStore.matchesSessionIdentity(account.id, session.url, session.login, session.sessionId, session.configId)
        }.getOrDefault(false)

        fun start() = socket.connect(
            onEvent = { incoming ->
                if (currentSession()) {
                    if (incoming.event.type == "call.incoming" && canListen()) {
                        IncomingSocketPayload.fromEvent(account, incoming, ::privateName)?.let(present)
                    } else if (incoming.event.type in setOf("call.cancel", "call.expire", "call.reject", "call.end")) {
                        val key = AccountCallKey(account.id, incoming.event.callId)
                        // The running call processes its own ordered events and terminal audio.
                        val running = GlobalCallAdmission.current()
                        if (running?.state != CallAdmissionState.Running || running.owner.key != key) {
                            cancel(account, CallCancellation(key, incoming.event.type))
                        }
                    }
                }
            },
            onOpen = { generation ->
                if (currentSession()) {
                    updateVisibility()
                    recover(generation)
                }
            },
            onError = { failure ->
                if (current() && failure.code == SessionReplacedReason) {
                    accountReader.execute { authStore.invalidateIfCurrent(account.id, account.session) }
                }
            },
        )

        private fun privateName(login: String): String? =
            cache.load(account).items.firstOrNull { it.login == login }?.customName

        private fun recover(generation: Long) {
            recovery?.cancel()
            val controller = IncomingCallController()
            val previous = controller.load(application)?.takeIf {
                it.action == null && it.invite.accountId == account.id && it.invite.sessionBinding.matches(account.session)
            }?.invite
            val session = account.session
            val request = Request.Builder().url(session.url.trimEnd('/') + "/api/active-call")
                .header("Authorization", Credentials.basic(session.login, session.token))
                .header("X-TiniTalk-Device-ID", DeviceIdentity.id(application))
                .apply { session.sessionId?.let { header(SessionIdHeader, it) } }.build()
            val call = http.newCall(request)
            recovery = call
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = Unit
                override fun onResponse(call: Call, response: Response) {
                    val snapshot = response.use {
                        if (it.code == 204) return@use ActiveIncomingSnapshot(null, null)
                        if (it.code != 200) return@use null
                        runCatching {
                            val json = JsonParser.parseString(it.body.string()).asJsonObject
                            val callId = json["call_id"].asString.takeIf(String::isNotBlank) ?: return@runCatching null
                            if (UUID.fromString(callId).toString() != callId.lowercase()) return@runCatching null
                            val pending = json.getAsJsonObject("incoming")
                            if (pending != null && pending["call_id"].asString != callId) return@runCatching null
                            ActiveIncomingSnapshot(callId, pending?.let {
                                IncomingSocketPayload.fromSnapshot(account, it, ::privateName)
                            })
                        }.getOrNull()
                    } ?: return
                    handler.post {
                        if (currentSession() && recovery === call && canListen() && socket.isOpen(generation)) {
                            // Absence clears only the invite seen when this request began, never a
                            // newer/accepted call. Old servers return the same call_id without incoming.
                            if (previous != null && snapshot.callId != previous.callId) {
                                val pending = controller.load(application)
                                val admission = GlobalCallAdmission.current()
                                if (pending?.invite?.owner == previous.owner && pending.action == null &&
                                    admission?.owner == previous.owner && admission.state == CallAdmissionState.Reserved
                                ) cancel(account, CallCancellation(previous.key, "call.cancel"))
                            }
                            snapshot.invite?.let(present)
                        }
                    }
                }
            })
        }

        override fun close() {
            visibleCall?.let { socket.sendVisibility(it, false) }
            visibleCall = null
            recovery?.cancel()
            recovery = null
            socket.close()
        }
    }

    private fun updateVisibility(withdrawHidden: Boolean = false) {
        handler.removeCallbacks(visibility)
        if (closed) return
        val incoming = IncomingCallController().load(application)?.takeIf { it.action == null }?.invite
        var renew = false
        var deferHide = false
        for (listener in listeners.values) {
            val visible = incoming?.takeIf {
                canListen() && it.accountId == listener.account.id &&
                    it.sessionBinding.matches(listener.account.session) &&
                    it.expiresAt.isAfter(java.time.Instant.now()) && IncomingCallScreenState.isShowing(it.owner)
            }?.callId
            if (visible == null && listener.visibleCall != null && !withdrawHidden) {
                deferHide = true
                continue
            }
            if (listener.visibleCall != visible) {
                listener.visibleCall?.let { listener.socket.sendVisibility(it, false) }
                listener.visibleCall = visible
            }
            if (visible != null) {
                listener.socket.sendVisibility(visible, true)
                renew = true
            }
        }
        if (deferHide && !hideScheduled) {
            hideScheduled = true
            handler.postDelayed(hideVisibility, 500)
        } else if (!deferHide) {
            hideScheduled = false
            handler.removeCallbacks(hideVisibility)
        }
        if (renew) handler.postDelayed(visibility, 1_000)
    }

    override fun close() {
        if (closed) return
        closed = true
        revision++
        handler.removeCallbacks(reconcile)
        handler.removeCallbacks(visibility)
        handler.removeCallbacks(hideVisibility)
        IncomingCallScreenState.removeObserver(screenObserver)
        preferences.unregisterOnSharedPreferenceChangeListener(accountsObserver)
        application.unregisterActivityLifecycleCallbacks(this)
        if (screenReceiverRegistered) application.unregisterReceiver(screenReceiver)
        listeners.values.toList().forEach(Listener::close)
        listeners.clear()
        networkObserver?.close()
        accountReader.shutdownNow()
        http.dispatcher.cancelAll()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) { resumed -= activity }
}

private data class ActiveIncomingSnapshot(val callId: String?, val invite: IncomingInvite?)
