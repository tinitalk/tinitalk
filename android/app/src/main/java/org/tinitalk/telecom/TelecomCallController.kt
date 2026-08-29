package org.tinitalk.telecom

import android.content.Context
import android.net.Uri
import android.telecom.DisconnectCause
import android.util.Log
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlScope
import androidx.core.telecom.CallControlResult
import androidx.core.telecom.CallsManager
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
    fun addOutgoing(callId: String, displayName: String, callbacks: TelecomCallCallbacks)
    fun answer(callId: String, onResult: (Boolean) -> Unit = {})
    fun reject(callId: String)
    fun setActive(callId: String, onResult: (Boolean) -> Unit = {})
    fun selectEndpoint(callId: String, endpointId: String)
    fun cancel(callId: String)
}

class TelecomCallController(private val registrar: TelecomRegistrar) {
    fun registerAudioOnly() {
        registrar.register(TelecomCapabilities.AudioOnly)
    }

    fun addIncoming(invite: IncomingInvite, callbacks: TelecomCallCallbacks) {
        registrar.addIncoming(invite, callbacks)
    }

    fun addOutgoing(callId: String, displayName: String, callbacks: TelecomCallCallbacks) {
        registrar.addOutgoing(callId, displayName, callbacks)
    }

    fun answer(callId: String, onResult: (Boolean) -> Unit = {}) = registrar.answer(callId, onResult)

    fun reject(callId: String) = registrar.reject(callId)

    fun setActive(callId: String, onResult: (Boolean) -> Unit = {}) = registrar.setActive(callId, onResult)

    fun selectEndpoint(callId: String, endpointId: String) = registrar.selectEndpoint(callId, endpointId)

    fun cancel(callId: String) = registrar.cancel(callId)
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
                    invite.callId,
                    IncomingCallNotifier(context)::cancel,
                )
            },
            reportTelecomFailure = { failure ->
                Log.w(TelecomLogTag, "System Telecom failed; keeping TiniTalk incoming call", failure)
            },
        )
        addCall(
            callId = invite.callId,
            displayName = invite.caller,
            direction = CallAttributesCompat.DIRECTION_INCOMING,
            callbacks = callbacks,
            expiresAt = invite.expiresAt,
            onExpired = failures::callExpired,
            onTelecomFailure = failures::telecomFailed,
        )
    }

    override fun addOutgoing(callId: String, displayName: String, callbacks: TelecomCallCallbacks) {
        addCall(
            callId = callId,
            displayName = displayName,
            direction = CallAttributesCompat.DIRECTION_OUTGOING,
            callbacks = callbacks,
        )
    }

    private fun addCall(
        callId: String,
        displayName: String,
        direction: Int,
        callbacks: TelecomCallCallbacks,
        expiresAt: Instant? = null,
        onExpired: () -> Unit = {},
        onTelecomFailure: (Throwable) -> Unit = {},
    ) {
        val session = TelecomSessions.prepare(callId) ?: return
        val owner = TelecomSessions.scope.launch(start = CoroutineStart.LAZY) {
            val expiry = expiresAt?.let {
                launch {
                    delay(Duration.between(Instant.now(), it).toMillis().coerceAtLeast(0))
                    try {
                        TelecomSessions.disconnect(callId, DisconnectCause.MISSED)
                        currentCoroutineContext().ensureActive()
                        onExpired()
                    } catch (failure: CancellationException) {
                        TelecomSessions.abortCancel(callId)
                        throw failure
                    }
                }
            }
            session.attachExpiry(expiry)
            try {
                callsManager.addCall(
                    CallAttributesCompat(
                        displayName = displayName.ifEmpty { "TiniTalk" },
                        address = Uri.parse("sip:$callId@tinitalk"),
                        direction = direction,
                        callType = CallAttributesCompat.CALL_TYPE_AUDIO_CALL,
                        callCapabilities = 0,
                    ),
                    onAnswer = {
                        TelecomSessions.cancelExpiry(callId)
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
                            TelecomSessions.updateEndpoints(callId, session, available)
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
                TelecomSessions.finishNormally(callId, session)
            }
        }
        session.attachOwner(owner)
        owner.start()
    }

    override fun answer(callId: String, onResult: (Boolean) -> Unit) {
        TelecomSessions.scope.launch {
            val success = runCatching {
                TelecomSessions.control(callId)
                    ?.answer(CallAttributesCompat.CALL_TYPE_AUDIO_CALL) is CallControlResult.Success
            }.getOrDefault(false)
            if (success) TelecomSessions.cancelExpiry(callId)
            onResult(success)
        }
    }

    override fun reject(callId: String) {
        TelecomSessions.scope.launch {
            TelecomSessions.disconnect(callId, DisconnectCause.REJECTED)
        }
    }

    override fun setActive(callId: String, onResult: (Boolean) -> Unit) {
        TelecomSessions.scope.launch {
            val success = runCatching {
                TelecomSessions.control(callId)?.setActive() is CallControlResult.Success
            }.getOrDefault(false)
            onResult(success)
        }
    }

    override fun selectEndpoint(callId: String, endpointId: String) {
        TelecomSessions.scope.launch {
            val control = TelecomSessions.control(callId) ?: return@launch
            TelecomSessions.endpoint(callId, endpointId)
                ?.let { endpoint -> control.requestEndpointChange(endpoint) }
        }
    }

    override fun cancel(callId: String) {
        TelecomSessions.scope.launch {
            TelecomSessions.disconnect(callId, DisconnectCause.REMOTE)
        }
    }
}

private fun androidx.core.telecom.CallEndpointCompat.toAudioEndpoint() =
    AudioEndpoint(identifier.toString(), name.toString(), type)

internal class EndpointCache<T> {
    private val values = ConcurrentHashMap<String, List<T>>()

    fun update(callId: String, endpoints: List<T>) {
        values[callId] = endpoints.toList()
    }

    fun find(callId: String, endpointId: String, identifier: (T) -> String): T? =
        values[callId]?.firstOrNull { identifier(it) == endpointId }

    fun remove(callId: String) {
        values.remove(callId)
    }
}

private object TelecomSessions {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sessions = ConcurrentHashMap<String, TelecomSession>()
    private val endpoints = EndpointCache<androidx.core.telecom.CallEndpointCompat>()

    fun prepare(callId: String): TelecomSession? {
        val session = TelecomSession()
        return if (sessions.putIfAbsent(callId, session) == null) session else null
    }

    suspend fun control(callId: String): CallControlScope? {
        val session = sessions[callId] ?: return null
        return try {
            session.control.await()
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            null
        }
    }

    suspend fun disconnect(callId: String, cause: Int) {
        val session = sessions[callId] ?: return
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
                forceRemove(callId, session)
            }
        } catch (failure: CancellationException) {
            session.abortCancel()
            throw failure
        }
    }

    fun cancelExpiry(callId: String) {
        sessions[callId]?.cancelExpiry()
    }

    fun abortCancel(callId: String) {
        sessions[callId]?.abortCancel()
    }

    fun updateEndpoints(
        callId: String,
        session: TelecomSession,
        available: List<androidx.core.telecom.CallEndpointCompat>,
    ) {
        if (sessions[callId] === session) endpoints.update(callId, available)
    }

    fun endpoint(callId: String, endpointId: String): androidx.core.telecom.CallEndpointCompat? =
        endpoints.find(callId, endpointId) { it.identifier.toString() }

    suspend fun disconnectDetached(control: CallControlScope, cause: Int) {
        disconnectControl(control, cause)
    }

    fun finishNormally(callId: String, session: TelecomSession) {
        if (sessions.remove(callId, session)) endpoints.remove(callId)
        session.finish()
    }

    private fun forceRemove(callId: String, session: TelecomSession) {
        if (sessions.remove(callId, session)) endpoints.remove(callId)
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
