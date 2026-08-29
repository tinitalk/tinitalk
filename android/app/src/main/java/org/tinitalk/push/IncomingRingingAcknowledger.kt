package org.tinitalk.push

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.tinitalk.call.CallCoordinator
import org.tinitalk.data.AndroidKeystoreTokenCipher
import org.tinitalk.data.AuthStore
import org.tinitalk.data.SessionReplacedReason
import org.tinitalk.data.SharedPreferencesKeyValueStore
import org.tinitalk.data.signal.SignalSocket
import org.tinitalk.telecom.signalingHttpClient
import java.io.Closeable
import java.time.Duration
import java.time.Instant
import okhttp3.OkHttpClient

class IncomingRingingAcknowledger(context: Context) : Closeable {
    private val context = context.applicationContext
    private var callId: String? = null
    private var socket: SignalSocket? = null
    private var httpClient: OkHttpClient? = null
    private val handler = Handler(Looper.getMainLooper())
    private var stopTask: Runnable? = null

    @Synchronized
    fun acknowledge(invite: IncomingInvite) {
        if (callId == invite.callId) return
        stop()
        val authStore = AuthStore(SharedPreferencesKeyValueStore(context), AndroidKeystoreTokenCipher())
        val session = authStore.load() ?: return
        val client = signalingHttpClient()
        val signal = SignalSocket(
            client,
            session,
            deviceId = DeviceRegistrar.deviceId(context),
        )
        callId = invite.callId
        socket = signal
        httpClient = client
        val timeout = Runnable { stop(invite.callId) }
        stopTask = timeout
        handler.postDelayed(
            timeout,
            Duration.between(Instant.now(), invite.expiresAt).toMillis().coerceAtLeast(0),
        )
        CallCoordinator(session.login, signal).restoreIncoming(invite.callId, invite.lastSeq) {
            stop(invite.callId)
        }
        runCatching {
            signal.connect(
                onEvent = {},
                onError = { failure ->
                    if (failure.code == SessionReplacedReason) authStore.invalidateIfCurrent(session)
                    stop(invite.callId)
                },
            )
        }
            .onFailure { stop(invite.callId) }
    }

    @Synchronized
    fun stop(expectedCallId: String? = null) {
        if (expectedCallId != null && callId != expectedCallId) return
        stopTask?.let(handler::removeCallbacks)
        stopTask = null
        callId = null
        socket?.close()
        httpClient?.dispatcher?.executorService?.shutdownNow()
        httpClient?.connectionPool?.evictAll()
        socket = null
        httpClient = null
    }

    override fun close() = stop()
}
