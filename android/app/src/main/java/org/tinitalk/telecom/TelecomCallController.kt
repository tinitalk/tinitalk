package org.tinitalk.telecom

import android.content.Context
import android.net.Uri
import android.telecom.DisconnectCause
import android.util.Log
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlScope
import androidx.core.telecom.CallControlResult
import androidx.core.telecom.CallsManager
import org.tinitalk.call.AccountCallKey
import org.tinitalk.push.IncomingCallNotifier
import org.tinitalk.push.IncomingInvite
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val TelecomLogTag = "TiniTalkCall"

enum class TelecomCapabilities {
    AudioOnly,
}

data class AudioEndpoint(val id: String, val name: String, val type: Int)

data class AudioEndpointState(
    val current: AudioEndpoint? = null,
    val available: List<AudioEndpoint> = emptyList(),
)

data class TelecomCallCallbacks(
    val onAnswer: () -> Unit = {},
    val onDisconnect: () -> Unit,
    val onActive: () -> Unit = {},
    val onInactive: () -> Unit = {},
    val onEndpointsChanged: (AudioEndpointState) -> Unit = {},
)

interface TelecomRegistrar {
    fun register(capabilities: TelecomCapabilities)
    fun addIncoming(invite: IncomingInvite, callbacks: TelecomCallCallbacks)
    fun addOutgoing(key: AccountCallKey, displayName: String, callbacks: TelecomCallCallbacks)
    fun answer(key: AccountCallKey, onResult: (Boolean) -> Unit = {})
    fun reject(key: AccountCallKey)
    fun setActive(key: AccountCallKey, onResult: (Boolean) -> Unit = {})
    fun selectEndpoint(key: AccountCallKey, endpointId: String)
    fun cancel(key: AccountCallKey)
}

class TelecomCallController(private val registrar: TelecomRegistrar) {
    fun registerAudioOnly() {
        registrar.register(TelecomCapabilities.AudioOnly)
    }

    fun addIncoming(invite: IncomingInvite, callbacks: TelecomCallCallbacks) {
        registrar.addIncoming(invite, callbacks)
    }

    fun addOutgoing(key: AccountCallKey, displayName: String, callbacks: TelecomCallCallbacks) {
        registrar.addOutgoing(key, displayName, callbacks)
    }

    fun answer(key: AccountCallKey, onResult: (Boolean) -> Unit = {}) = registrar.answer(key, onResult)

    fun reject(key: AccountCallKey) = registrar.reject(key)

    fun setActive(key: AccountCallKey, onResult: (Boolean) -> Unit = {}) = registrar.setActive(key, onResult)

    fun selectEndpoint(key: AccountCallKey, endpointId: String) = registrar.selectEndpoint(key, endpointId)

    fun cancel(key: AccountCallKey) = registrar.cancel(key)
}

internal class IncomingTelecomFailureHandler(
    private val finishPresentation: () -> Unit,
    private val reportTelecomFailure: (Throwable) -> Unit,
) {
    fun telecomFailed(failure: Throwable) {
        // Android 13 and older use the legacy Telecom implementation, which can reject or
        // time out independently. Telecom is optional, so TiniTalk must keep ringing itself.
        reportTelecomFailure(failure)
    }

    fun callExpired() = finishPresentation()
}

class AndroidTelecomRegistrar(context: Context) : TelecomRegistrar {
    private val context = context.applicationContext
    private val callsManager = CallsManager(context.applicationContext)

    override fun register(capabilities: TelecomCapabilities) {
        callsManager.registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE)
    }

    override fun addIncoming(invite: IncomingInvite, callbacks: TelecomCallCallbacks) {
        val failures = IncomingTelecomFailureHandler(
            finishPresentation = {
                IncomingCallController().finishTerminalPresentation(
                    context,
                    invite.owner,
                    IncomingCallNotifier(context)::cancel,
                )
            },
            reportTelecomFailure = { failure ->
                Log.w(TelecomLogTag, "System Telecom failed; keeping TiniTalk incoming call", failure)
            },
        )
        addCall(
            key = invite.key,
            displayName = invite.caller,
            direction = CallAttributesCompat.DIRECTION_INCOMING,
            callbacks = callbacks,
            expiresAt = invite.expiresAt,
            onExpired = failures::callExpired,
            onTelecomFailure = failures::telecomFailed,
        )
    }

    override fun addOutgoing(key: AccountCallKey, displayName: String, callbacks: TelecomCallCallbacks) {
        addCall(
            key = key,
            displayName = displayName,
            direction = CallAttributesCompat.DIRECTION_OUTGOING,
            callbacks = callbacks,
        )
    }

    private fun addCall(
        key: AccountCallKey,
        displayName: String,
        direction: Int,
        callbacks: TelecomCallCallbacks,
        expiresAt: Instant? = null,
        onExpired: () -> Unit = {},
        onTelecomFailure: (Throwable) -> Unit = {},
    ) {
        val session = TelecomSessions.prepare(key) ?: return
        val owner = TelecomSessions.scope.launch(start = CoroutineStart.LAZY) {
            val expiry = expiresAt?.let {
                launch {
                    delay(Duration.between(Instant.now(), it).toMillis().coerceAtLeast(0))
                    try {
                        TelecomSessions.disconnect(key, DisconnectCause.MISSED)
                        currentCoroutineContext().ensureActive()
                        onExpired()
                    } catch (failure: CancellationException) {
                        TelecomSessions.abortCancel(key)
                        throw failure
                    }
                }
            }
            session.attachExpiry(expiry)
            try {
                callsManager.addCall(
                    CallAttributesCompat(
                        displayName = displayName.ifEmpty { "TiniTalk" },
                        address = Uri.parse("sip:${Uri.encode(key.localId())}@tinitalk"),
                        direction = direction,
                        callType = CallAttributesCompat.CALL_TYPE_AUDIO_CALL,
                        callCapabilities = 0,
                    ),
                    onAnswer = {
                        TelecomSessions.cancelExpiry(key)
                        callbacks.onAnswer()
                    },
                    onDisconnect = { callbacks.onDisconnect() },
                    onSetActive = { callbacks.onActive() },
                    onSetInactive = { callbacks.onInactive() },
                ) {
                    val control = this
                    if (!session.publish(control)) {
                        TelecomSessions.scope.launch {
                            TelecomSessions.disconnectDetached(control, DisconnectCause.REMOTE)
                        }
                        return@addCall
                    }
                    launch {
                        combine(currentCallEndpoint, availableEndpoints) { current, available ->
                            TelecomSessions.updateEndpoints(key, session, available)
                            AudioEndpointState(
                                current = current.toAudioEndpoint(),
                                available = available.map { it.toAudioEndpoint() },
                            )
                        }.collect(callbacks.onEndpointsChanged)
                    }
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                session.fail(failure)
                onTelecomFailure(failure)
            } finally {
                TelecomSessions.finishNormally(key, session)
            }
        }
        session.attachOwner(owner)
        owner.start()
    }

    override fun answer(key: AccountCallKey, onResult: (Boolean) -> Unit) {
        TelecomSessions.scope.launch {
            val success = runCatching {
                TelecomSessions.control(key)
                    ?.answer(CallAttributesCompat.CALL_TYPE_AUDIO_CALL) is CallControlResult.Success
            }.getOrDefault(false)
            if (success) TelecomSessions.cancelExpiry(key)
            onResult(success)
        }
    }

    override fun reject(key: AccountCallKey) {
        TelecomSessions.scope.launch {
            TelecomSessions.disconnect(key, DisconnectCause.REJECTED)
        }
    }

    override fun setActive(key: AccountCallKey, onResult: (Boolean) -> Unit) {
        TelecomSessions.scope.launch {
            val success = runCatching {
                TelecomSessions.control(key)?.setActive() is CallControlResult.Success
            }.getOrDefault(false)
            onResult(success)
        }
    }

    override fun selectEndpoint(key: AccountCallKey, endpointId: String) {
        TelecomSessions.scope.launch {
            val control = TelecomSessions.control(key) ?: return@launch
            TelecomSessions.endpoint(key, endpointId)
                ?.let { endpoint -> control.requestEndpointChange(endpoint) }
        }
    }

    override fun cancel(key: AccountCallKey) {
        TelecomSessions.scope.launch {
            TelecomSessions.disconnect(key, DisconnectCause.REMOTE)
        }
    }
}

private fun androidx.core.telecom.CallEndpointCompat.toAudioEndpoint() =
    AudioEndpoint(identifier.toString(), name.toString(), type)

internal class EndpointCache<T> {
    private val values = ConcurrentHashMap<AccountCallKey, List<T>>()

    fun update(key: AccountCallKey, endpoints: List<T>) {
        values[key] = endpoints.toList()
    }

    fun find(key: AccountCallKey, endpointId: String, identifier: (T) -> String): T? =
        values[key]?.firstOrNull { identifier(it) == endpointId }

    fun remove(key: AccountCallKey) {
        values.remove(key)
    }
}

private object TelecomSessions {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sessions = ConcurrentHashMap<AccountCallKey, TelecomSession>()
    private val endpoints = EndpointCache<androidx.core.telecom.CallEndpointCompat>()

    fun prepare(key: AccountCallKey): TelecomSession? {
        val session = TelecomSession()
        return if (sessions.putIfAbsent(key, session) == null) session else null
    }

    suspend fun control(key: AccountCallKey): CallControlScope? {
        val session = sessions[key] ?: return null
        return try {
            session.control.await()
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            null
        }
    }

    suspend fun disconnect(key: AccountCallKey, cause: Int) {
        val session = sessions[key] ?: return
        if (!session.beginCancel()) return
        try {
            val control = try {
                withTimeoutOrNull(ControlReadyTimeoutMillis) { session.control.await() }
            } catch (failure: CancellationException) {
                currentCoroutineContext().ensureActive()
                null
            } catch (_: Exception) {
                null
            }
            if (control == null || !disconnectControl(control, cause)) {
                forceRemove(key, session)
            }
        } catch (failure: CancellationException) {
            session.abortCancel()
            throw failure
        }
    }

    fun cancelExpiry(key: AccountCallKey) {
        sessions[key]?.cancelExpiry()
    }

    fun abortCancel(key: AccountCallKey) {
        sessions[key]?.abortCancel()
    }

    fun updateEndpoints(
        key: AccountCallKey,
        session: TelecomSession,
        available: List<androidx.core.telecom.CallEndpointCompat>,
    ) {
        if (sessions[key] === session) endpoints.update(key, available)
    }

    fun endpoint(key: AccountCallKey, endpointId: String): androidx.core.telecom.CallEndpointCompat? =
        endpoints.find(key, endpointId) { it.identifier.toString() }

    suspend fun disconnectDetached(control: CallControlScope, cause: Int) {
        disconnectControl(control, cause)
    }

    fun finishNormally(key: AccountCallKey, session: TelecomSession) {
        if (sessions.remove(key, session)) endpoints.remove(key)
        session.finish()
    }

    private fun forceRemove(key: AccountCallKey, session: TelecomSession) {
        if (sessions.remove(key, session)) endpoints.remove(key)
        session.forceClose()
    }

    private suspend fun disconnectControl(control: CallControlScope, cause: Int): Boolean =
        withTimeoutOrNull(DisconnectTimeoutMillis) {
            repeat(DisconnectAttempts) { attempt ->
                val result = try {
                    control.disconnect(DisconnectCause(cause))
                } catch (failure: CancellationException) {
                    throw failure
                } catch (_: Exception) {
                    null
                }
                if (result is CallControlResult.Success) return@withTimeoutOrNull true
                if (attempt + 1 < DisconnectAttempts) delay(DisconnectRetryDelayMillis)
            }
            false
        } ?: false

    private const val ControlReadyTimeoutMillis = 6_000L
    private const val DisconnectTimeoutMillis = 2_000L
    private const val DisconnectRetryDelayMillis = 150L
    private const val DisconnectAttempts = 3
}

private class TelecomSession {
    val control = CompletableDeferred<CallControlScope>()
    private val cancelStarted = AtomicBoolean(false)
    private val forceClosed = AtomicBoolean(false)
    private var owner: Job? = null
    private var expiry: Job? = null

    fun attachOwner(job: Job) {
        val cancel = synchronized(this) {
            owner = job
            forceClosed.get()
        }
        if (cancel) job.cancel()
    }

    fun attachExpiry(job: Job?) {
        if (job == null) return
        val cancel = synchronized(this) {
            expiry?.cancel()
            expiry = job
            forceClosed.get()
        }
        if (cancel) job.cancel()
    }

    fun publish(value: CallControlScope): Boolean = synchronized(this) {
        if (forceClosed.get()) return false
        control.complete(value)
    }

    fun fail(failure: Throwable) {
        control.completeExceptionally(failure)
    }

    fun beginCancel(): Boolean = cancelStarted.compareAndSet(false, true)

    fun abortCancel() {
        cancelStarted.compareAndSet(true, false)
    }

    fun cancelExpiry() {
        val job = synchronized(this) { expiry.also { expiry = null } }
        job?.cancel()
    }

    fun finish() {
        cancelExpiry()
    }

    fun forceClose() {
        val cleanup = synchronized(this) {
            if (!forceClosed.compareAndSet(false, true)) return
            val jobs = owner to expiry
            expiry = null
            jobs
        }
        control.cancel()
        cleanup.second?.cancel()
        cleanup.first?.cancel()
    }
}
