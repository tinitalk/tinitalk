package org.tinitalk.push

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.tinitalk.call.CallCoordinator
import org.tinitalk.call.AccountCallOwner
import org.tinitalk.call.resolvePinnedCallSession
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
    private var owner: AccountCallOwner? = null
    private var socket: SignalSocket? = null
    private var httpClient: OkHttpClient? = null
    private val handler = Handler(Looper.getMainLooper())
    private var stopTask: Runnable? = null

    @Synchronized
    fun acknowledge(invite: IncomingInvite) {
        start(invite) { coordinator ->
            coordinator.restoreIncoming(invite.callId, invite.lastSeq) {
                stop(invite.owner)
            }
        }
    }

    @Synchronized
    fun rejectBusy(invite: IncomingInvite) {
        start(invite) { coordinator ->
            rejectCompetingInvite(coordinator, invite) {
                stop(invite.owner)
            }
        }
    }

    private fun start(invite: IncomingInvite, prepare: (CallCoordinator) -> Unit) {
        if (owner == invite.owner) return
        stop()
        val authStore = AuthStore(SharedPreferencesKeyValueStore(context), AndroidKeystoreTokenCipher())
        val session = resolvePinnedCallSession(authStore, invite.accountId, invite.sessionBinding) ?: return
        val client = signalingHttpClient()
        val signal = SignalSocket(
            client,
            session,
            deviceId = DeviceIdentity.id(context),
        )
        owner = invite.owner
        socket = signal
        httpClient = client
        val timeout = Runnable { stop(invite.owner) }
        stopTask = timeout
        handler.postDelayed(
            timeout,
            Duration.between(Instant.now(), invite.expiresAt).toMillis().coerceAtLeast(0),
        )
        prepare(CallCoordinator(session.login, signal, serverFeatures = session.features, accountId = invite.accountId))
        runCatching {
            signal.connect(
                onEvent = {},
                onError = { failure ->
                    if (failure.code == SessionReplacedReason) {
                        authStore.invalidateIfCurrent(invite.accountId, session)
                    }
                    stop(invite.owner)
                },
            )
        }
            .onFailure { stop(invite.owner) }
    }

    @Synchronized
    fun stop(expectedOwner: AccountCallOwner? = null) {
        if (expectedOwner != null && owner != expectedOwner) return
        stopTask?.let(handler::removeCallbacks)
        stopTask = null
        owner = null
        socket?.close()
        httpClient?.dispatcher?.executorService?.shutdownNow()
        httpClient?.connectionPool?.evictAll()
        socket = null
        httpClient = null
    }

    override fun close() = stop()
}

internal fun rejectCompetingInvite(
    coordinator: CallCoordinator,
    invite: IncomingInvite,
    onSettled: (() -> Unit)? = null,
) {
    coordinator.restoreIncoming(invite.callId, invite.lastSeq, acknowledgeRinging = false)
    coordinator.resume()
    coordinator.reject(onSettled)
}
