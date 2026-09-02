package org.tinitalk.call

import android.os.SystemClock
import org.tinitalk.data.AccountId
import org.tinitalk.data.ContactAddress
import org.tinitalk.media.MediaConnectionState
import org.tinitalk.telecom.AudioEndpoint
import org.tinitalk.telecom.AudioEndpointState
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet

enum class CallDirection {
    Incoming,
    Outgoing,
}

enum class ConnectionHealth {
    None,
    Connecting,
    Good,
    Poor,
    Reconnecting,
}

enum class CallEndReason {
    LocalHangup,
    RemoteHangup,
    Rejected,
    Cancelled,
    TimedOut,
    Busy,
    ConnectionLost,
    Failed,
}

data class CallPeer(
    val displayName: String,
    val login: String? = null,
    val contactAddress: ContactAddress? = null,
)

data class CallUiState(
    val accountId: AccountId? = null,
    val callId: String? = null,
    val peer: CallPeer? = null,
    val direction: CallDirection? = null,
    val phase: CallPhase = CallPhase.Idle,
    val connectedAtElapsedMs: Long? = null,
    val endedAtElapsedMs: Long? = null,
    val muted: Boolean = false,
    val currentAudioEndpoint: AudioEndpoint? = null,
    val availableAudioEndpoints: List<AudioEndpoint> = emptyList(),
    val connectionHealth: ConnectionHealth = ConnectionHealth.None,
    val endReason: CallEndReason? = null,
) {
    val callKey: AccountCallKey?
        get() = accountId?.let { id -> callId?.let { AccountCallKey(id, it) } }

    fun onMediaConnection(state: MediaConnectionState, nowElapsedMs: Long): CallUiState =
        when (state) {
            MediaConnectionState.Connecting -> copy(
                connectionHealth = if (connectedAtElapsedMs == null) {
                    ConnectionHealth.Connecting
                } else {
                    ConnectionHealth.Reconnecting
                },
            )
            MediaConnectionState.Connected -> copy(
                connectedAtElapsedMs = connectedAtElapsedMs ?: nowElapsedMs,
                connectionHealth = ConnectionHealth.Good,
            )
            MediaConnectionState.Disconnected,
            MediaConnectionState.Failed -> copy(
                connectionHealth = if (connectedAtElapsedMs == null) {
                    ConnectionHealth.Connecting
                } else {
                    ConnectionHealth.Reconnecting
                },
            )
            MediaConnectionState.Closed -> copy(connectionHealth = ConnectionHealth.None)
        }

    fun onEnded(reason: CallEndReason, nowElapsedMs: Long): CallUiState = copy(
        phase = CallPhase.Ended,
        endedAtElapsedMs = connectedAtElapsedMs?.let { connectedAt ->
            endedAtElapsedMs ?: nowElapsedMs.coerceAtLeast(connectedAt)
        },
        connectionHealth = ConnectionHealth.None,
        endReason = endReason ?: reason,
    )

    fun durationMillis(nowElapsedMs: Long): Long? = connectedAtElapsedMs?.let { connectedAt ->
        ((endedAtElapsedMs ?: nowElapsedMs) - connectedAt).coerceAtLeast(0L)
    }

    fun durationText(nowElapsedMs: Long): String? = durationMillis(nowElapsedMs)?.let(::formatCallDuration)
}

fun formatCallDuration(durationMillis: Long): String {
    val totalSeconds = durationMillis.coerceAtLeast(0L) / 1_000L
    val seconds = totalSeconds % 60L
    val minutes = (totalSeconds / 60L) % 60L
    val hours = totalSeconds / 3_600L
    return if (hours > 0L) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
    }
}

internal fun outgoingVisibleState(
    state: CallUiState,
    login: String,
    displayName: String,
    contactAddress: ContactAddress? = null,
): CallUiState {
    val belongsToCurrentCall = state.direction == CallDirection.Outgoing && state.peer?.login == login
    if (state.phase != CallPhase.Idle && belongsToCurrentCall) return state
    return CallUiState(
        peer = CallPeer(displayName.ifBlank { login }, login, contactAddress),
        direction = CallDirection.Outgoing,
        phase = CallPhase.Connecting,
    )
}

internal fun shouldDismissIncomingOverlay(activityVisible: Boolean, state: CallUiState): Boolean =
    activityVisible && state.direction == CallDirection.Incoming && state.phase == CallPhase.Ringing

internal enum class CallScreenAction {
    Answer,
    Reject,
    End,
}

internal class CallScreenActionGate {
    private var locked: Lock? = null

    fun lock(action: CallScreenAction, callKey: AccountCallKey): Boolean {
        if (locked?.callKey == callKey) return false
        locked = Lock(action, callKey)
        return true
    }

    fun onCallState(state: CallUiState) {
        val current = locked ?: return
        if (current.action == CallScreenAction.Answer &&
            current.callKey == state.callKey &&
            state.phase == CallPhase.Active
        ) {
            locked = null
        }
    }

    fun reset() {
        locked = null
    }

    fun isLocked(callKey: AccountCallKey): Boolean = locked?.callKey == callKey

    private data class Lock(val action: CallScreenAction, val callKey: AccountCallKey)
}

object CallUiStateStore {
    private val listeners = CopyOnWriteArraySet<(CallUiState) -> Unit>()

    @Volatile
    private var current = CallUiState()

    fun snapshot(): CallUiState = current

    fun observe(listener: (CallUiState) -> Unit) {
        listeners += listener
        listener(current)
    }

    fun removeObserver(listener: (CallUiState) -> Unit) {
        listeners -= listener
    }

    @Synchronized
    fun begin(callKey: AccountCallKey, peer: CallPeer, direction: CallDirection, phase: CallPhase) {
        publish(
            CallUiState(
                accountId = callKey.accountId,
                callId = callKey.callId,
                peer = peer,
                direction = direction,
                phase = phase,
                connectionHealth = if (phase == CallPhase.Active || phase == CallPhase.Connecting) {
                    ConnectionHealth.Connecting
                } else {
                    ConnectionHealth.None
                },
            ),
        )
    }

    @Synchronized
    fun sync(snapshot: CallSnapshot, endReason: CallEndReason? = null) {
        val now = SystemClock.elapsedRealtime()
        val base = current.takeIf { it.callKey == snapshot.callKey } ?: CallUiState(
            accountId = snapshot.accountId,
            callId = snapshot.callId,
        )
        val next = if (snapshot.phase == CallPhase.Ended) {
            base.copy(accountId = snapshot.accountId, callId = snapshot.callId)
                .onEnded(endReason ?: CallEndReason.Failed, now)
        } else {
            base.copy(
                accountId = snapshot.accountId,
                callId = snapshot.callId,
                phase = snapshot.phase,
                endReason = null,
                connectionHealth = when {
                    snapshot.phase == CallPhase.Idle || snapshot.phase == CallPhase.Ringing -> ConnectionHealth.None
                    base.connectedAtElapsedMs == null -> ConnectionHealth.Connecting
                    else -> base.connectionHealth
                },
            )
        }
        publish(next)
    }

    @Synchronized
    fun onMediaConnection(state: MediaConnectionState) {
        publish(current.onMediaConnection(state, SystemClock.elapsedRealtime()))
    }

    @Synchronized
    fun setMuted(muted: Boolean) {
        publish(current.copy(muted = muted))
    }

    @Synchronized
    fun setAudioEndpoints(callKey: AccountCallKey, endpoints: AudioEndpointState) {
        val state = current
        if (state.callKey != callKey) return
        publish(
            state.copy(
                currentAudioEndpoint = endpoints.current,
                availableAudioEndpoints = endpoints.available,
            ),
        )
    }

    @Synchronized
    fun clearAudioEndpoints() {
        publish(current.copy(currentAudioEndpoint = null, availableAudioEndpoints = emptyList()))
    }

    @Synchronized
    fun setConnectionHealth(callKey: AccountCallKey, health: ConnectionHealth) {
        val state = current
        if (state.callKey != callKey || state.phase != CallPhase.Active) return
        publish(state.copy(connectionHealth = health))
    }

    @Synchronized
    fun reset() {
        publish(CallUiState())
    }

    @Synchronized
    fun reset(callKey: AccountCallKey): Boolean {
        if (current.callKey != callKey) return false
        publish(CallUiState())
        return true
    }

    private fun publish(state: CallUiState) {
        current = state
        listeners.forEach { it(state) }
    }
}
