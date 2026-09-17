package org.tinitalk.telecom

import android.os.Handler
import android.util.Log
import org.tinitalk.call.CallPhase
import org.tinitalk.call.CallServiceState
import org.tinitalk.call.CallUiStateStore
import org.tinitalk.call.callTransportRoute
import org.tinitalk.media.CallStats
import org.tinitalk.media.ConnectionHealthClassifier
import org.tinitalk.media.MediaConnectionState

/** A captured media runtime; dispatch and ownership checks stay with its service. */
internal class CallStatsSource(
    val dispatch: (() -> Unit) -> Boolean,
    val getStats: ((CallStats) -> Unit) -> Unit,
    val isCurrent: () -> Boolean,
)

internal class CallStatsMonitor(
    private val handler: Handler,
    private val source: () -> CallStatsSource?,
) {
    private var polling = false
    private val requestGate = MediaStatsRequestGate()
    @Volatile private var session: MediaStatsSession? = null
    private val healthClassifier = ConnectionHealthClassifier()

    private val statsTask = object : Runnable {
        override fun run() {
            if (!polling) return
            val activeCallId = CallServiceState.snapshot()
                .takeIf { it.phase == CallPhase.Active }
                ?.callId
            val currentSource = source()
            val currentSession = session
            val request = if (
                activeCallId != null && currentSource != null && currentSession?.callId == activeCallId
            ) {
                requestGate.begin(currentSession)
            } else {
                null
            }
            if (activeCallId != null && currentSource != null && request != null) {
                val accepted = currentSource.dispatch dispatch@{
                    if (!currentSource.isCurrent()) {
                        requestGate.complete(request)
                        return@dispatch
                    }
                    runCatching {
                        currentSource.getStats { stats ->
                            handler.post {
                                val accepted = requestGate.complete(request)
                                val snapshot = CallServiceState.snapshot()
                                val stillActive = polling && snapshot.phase == CallPhase.Active &&
                                    snapshot.callId == activeCallId
                                if (accepted && stillActive && currentSource.isCurrent()) {
                                    Log.i("TiniTalkCall", CallDiagnostics.format(stats))
                                    stats.videoDiagnostics.forEach { Log.i("TiniTalkVideo", it) }
                                    val currentHealth = CallUiStateStore.snapshot().connectionHealth
                                    val health = healthClassifier.update(stats, currentHealth)
                                    val route = callTransportRoute(
                                        stats.localCandidateType,
                                        stats.remoteCandidateType,
                                    )
                                    snapshot.callKey?.let {
                                        CallUiStateStore.setConnectionDiagnostics(it, health, route)
                                    }
                                }
                            }
                        }
                    }.onFailure {
                        requestGate.complete(request)
                    }
                }
                if (!accepted) requestGate.complete(request)
            }
            if (polling) handler.postDelayed(this, CallDiagnostics.IntervalMillis)
        }
    }

    // Session/epoch operations also run on the media dispatcher; the gate is synchronized.
    fun openSession(callId: String): MediaStatsSession = requestGate.openSession(callId).also { session = it }
    fun owns(candidate: MediaStatsSession): Boolean = session === candidate
    fun onConnection(candidate: MediaStatsSession, state: MediaConnectionState): MediaConnectionEpoch? =
        requestGate.onConnection(candidate, state)

    // Polling and quality state are confined to the handler's thread, as in the service.
    fun resetHealthOnConnection(connection: MediaConnectionEpoch) {
        if (!connection.transportReady || connection.becameReady) resetHealth()
    }

    fun resetHealth() = healthClassifier.reset()

    fun start() {
        if (polling) return
        polling = true
        handler.postDelayed(statsTask, CallDiagnostics.IntervalMillis)
    }

    fun stop() {
        polling = false
        handler.removeCallbacks(statsTask)
        requestGate.reset()
        session = null
        resetHealth()
    }
}
