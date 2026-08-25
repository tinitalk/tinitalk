package org.tinitalk.telecom

import android.content.Context
import android.net.Uri
import android.telecom.DisconnectCause
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlScope
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
import kotlinx.coroutines.launch

enum class TelecomCapabilities {
    AudioOnly,
}

interface TelecomRegistrar {
    fun register(capabilities: TelecomCapabilities)
    fun addIncoming(invite: IncomingInvite, onAnswer: () -> Unit, onDisconnect: () -> Unit)
    fun answer(callId: String)
    fun reject(callId: String)
    fun cancel(callId: String)
}

class TelecomCallController(private val registrar: TelecomRegistrar) {
    fun registerAudioOnly() {
        registrar.register(TelecomCapabilities.AudioOnly)
    }

    fun addIncoming(invite: IncomingInvite, onAnswer: () -> Unit, onDisconnect: () -> Unit) {
        registrar.addIncoming(invite, onAnswer, onDisconnect)
    }

    fun answer(callId: String) = registrar.answer(callId)

    fun reject(callId: String) = registrar.reject(callId)

    fun cancel(callId: String) = registrar.cancel(callId)
}

class AndroidTelecomRegistrar(context: Context) : TelecomRegistrar {
    private val context = context.applicationContext
    private val callsManager = CallsManager(context.applicationContext)

    override fun register(capabilities: TelecomCapabilities) {
        callsManager.registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE)
    }

    override fun addIncoming(invite: IncomingInvite, onAnswer: () -> Unit, onDisconnect: () -> Unit) {
        val session = TelecomSessions.prepare(invite.callId) ?: return
        TelecomSessions.scope.launch {
            val expiry = launch {
                delay(Duration.between(Instant.now(), invite.expiresAt).toMillis().coerceAtLeast(0))
                TelecomSessions.disconnect(invite.callId, DisconnectCause.MISSED)
            }
            try {
                callsManager.addCall(
                    CallAttributesCompat(
                        displayName = invite.caller.ifEmpty { "TiniTalk" },
                        address = Uri.parse("sip:${invite.callId}@tinitalk"),
                        direction = CallAttributesCompat.DIRECTION_INCOMING,
                        callType = CallAttributesCompat.CALL_TYPE_AUDIO_CALL,
                        callCapabilities = 0,
                    ),
                    onAnswer = { onAnswer() },
                    onDisconnect = { onDisconnect() },
                    onSetActive = {},
                    onSetInactive = {},
                ) {
                    session.complete(this)
                }
            } catch (_: Exception) {
                session.completeExceptionally(IllegalStateException("Telecom rejected the call"))
                IncomingCallNotifier(context).cancel()
                IncomingCallController().clear(context)
            } finally {
                expiry.cancel()
                TelecomSessions.remove(invite.callId, session)
            }
        }
    }

    override fun answer(callId: String) {
        TelecomSessions.scope.launch {
            TelecomSessions.control(callId)?.answer(CallAttributesCompat.CALL_TYPE_AUDIO_CALL)
        }
    }

    override fun reject(callId: String) {
        TelecomSessions.scope.launch {
            TelecomSessions.disconnect(callId, DisconnectCause.REJECTED)
        }
    }

    override fun cancel(callId: String) {
        TelecomSessions.scope.launch {
            TelecomSessions.disconnect(callId, DisconnectCause.REMOTE)
        }
    }
}

private object TelecomSessions {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sessions = ConcurrentHashMap<String, CompletableDeferred<CallControlScope>>()

    fun prepare(callId: String): CompletableDeferred<CallControlScope>? {
        val session = CompletableDeferred<CallControlScope>()
        return if (sessions.putIfAbsent(callId, session) == null) session else null
    }

    suspend fun control(callId: String): CallControlScope? =
        runCatching { sessions[callId]?.await() }.getOrNull()

    suspend fun disconnect(callId: String, cause: Int) {
        control(callId)?.disconnect(DisconnectCause(cause))
    }

    fun remove(callId: String, session: CompletableDeferred<CallControlScope>) {
        sessions.remove(callId, session)
    }
}
