package org.tinitalk.telecom

import android.content.Context
import android.net.Uri
import android.telecom.DisconnectCause
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlScope
import androidx.core.telecom.CallControlResult
import androidx.core.telecom.CallsManager
import org.tinitalk.push.IncomingCallNotifier
import org.tinitalk.push.IncomingInvite
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

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

class AndroidTelecomRegistrar(context: Context) : TelecomRegistrar {
    private val context = context.applicationContext
    private val callsManager = CallsManager(context.applicationContext)

    override fun register(capabilities: TelecomCapabilities) {
        callsManager.registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE)
    }

    override fun addIncoming(invite: IncomingInvite, callbacks: TelecomCallCallbacks) {
        addCall(
            callId = invite.callId,
            displayName = invite.caller,
            direction = CallAttributesCompat.DIRECTION_INCOMING,
            callbacks = callbacks,
            expiresAt = invite.expiresAt,
            onFailure = {
                IncomingCallNotifier(context).cancel()
                IncomingCallController().clear(context, invite.callId)
            },
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
        onFailure: () -> Unit = {},
    ) {
        val session = TelecomSessions.prepare(callId) ?: return
        TelecomSessions.scope.launch {
            val expiry = expiresAt?.let {
                launch {
                    delay(Duration.between(Instant.now(), it).toMillis().coerceAtLeast(0))
                    TelecomSessions.disconnect(callId, DisconnectCause.MISSED)
                    onFailure()
                }
            }
            TelecomSessions.setExpiry(callId, expiry)
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
                    session.complete(this)
                    launch {
                        combine(currentCallEndpoint, availableEndpoints) { current, available ->
                            AudioEndpointState(
                                current = current.toAudioEndpoint(),
                                available = available.map { it.toAudioEndpoint() },
                            )
                        }.collect(callbacks.onEndpointsChanged)
                    }
                }
            } catch (_: Exception) {
                session.completeExceptionally(IllegalStateException("Telecom rejected the call"))
                onFailure()
            } finally {
                expiry?.cancel()
                TelecomSessions.remove(callId, session)
            }
        }
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
            control.availableEndpoints
                .first()
                .firstOrNull { it.identifier.toString() == endpointId }
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

private object TelecomSessions {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sessions = ConcurrentHashMap<String, CompletableDeferred<CallControlScope>>()
    private val expiries = ConcurrentHashMap<String, Job>()

    fun prepare(callId: String): CompletableDeferred<CallControlScope>? {
        val session = CompletableDeferred<CallControlScope>()
        return if (sessions.putIfAbsent(callId, session) == null) session else null
    }

    suspend fun control(callId: String): CallControlScope? =
        runCatching { sessions[callId]?.await() }.getOrNull()

    suspend fun disconnect(callId: String, cause: Int) {
        control(callId)?.disconnect(DisconnectCause(cause))
    }

    fun setExpiry(callId: String, expiry: Job?) {
        if (expiry != null) expiries.put(callId, expiry)?.cancel()
    }

    fun cancelExpiry(callId: String) {
        expiries.remove(callId)?.cancel()
    }

    fun remove(callId: String, session: CompletableDeferred<CallControlScope>) {
        cancelExpiry(callId)
        sessions.remove(callId, session)
    }
}
