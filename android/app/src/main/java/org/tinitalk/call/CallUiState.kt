package org.tinitalk.call

import android.os.SystemClock
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
)

data class CallUiState(
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

internal fun outgoingVisibleState(state: CallUiState, login: String, displayName: String): CallUiState {
    val belongsToCurrentCall = state.direction == CallDirection.Outgoing && state.peer?.login == login
    if (state.phase != CallPhase.Idle && belongsToCurrentCall) return state
    return CallUiState(
        peer = CallPeer(displayName.ifBlank { login }, login),
        direction = CallDirection.Outgoing,
        phase = CallPhase.Connecting,
    )
}

internal fun shouldDismissIncomingOverlay(activityVisible: Boolean, state: CallUiState): Boolean =
    activityVisible && state.direction == CallDirection.Incoming && state.phase == CallPhase.Ringing

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
    fun begin(callId: String, peer: CallPeer, direction: CallDirection, phase: CallPhase) {
        publish(
            CallUiState(
                callId = callId,
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
        val base = current.takeIf { it.callId == snapshot.callId } ?: CallUiState(callId = snapshot.callId)
        val next = if (snapshot.phase == CallPhase.Ended) {
            base.copy(callId = snapshot.callId).onEnded(endReason ?: CallEndReason.Failed, now)
        } else {
            base.copy(
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
    fun setAudioEndpoints(callId: String, endpoints: AudioEndpointState) {
        val state = current
        if (state.callId != callId) return
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
    fun setConnectionHealth(callId: String, health: ConnectionHealth) {
        val state = current
        if (state.callId != callId || state.phase != CallPhase.Active) return
        publish(state.copy(connectionHealth = health))
    }

    @Synchronized
    fun reset() {
        publish(CallUiState())
    }

    @Synchronized
    fun reset(callId: String): Boolean {
        if (current.callId != callId) return false
        publish(CallUiState())
        return true
    }

    private fun publish(state: CallUiState) {
        current = state
        listeners.forEach { it(state) }
    }
}
