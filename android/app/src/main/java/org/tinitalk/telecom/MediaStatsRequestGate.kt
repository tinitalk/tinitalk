package org.tinitalk.telecom

import org.tinitalk.media.MediaConnectionState

internal data class MediaStatsSession(
    val callId: String,
    internal val id: Long,
)

internal data class MediaStatsRequestToken(
    val callId: String,
    internal val sessionId: Long,
    internal val epoch: Long,
    internal val requestId: Long,
)

internal data class MediaConnectionEpoch(
    val epoch: Long,
    val transportReady: Boolean,
    val becameReady: Boolean,
)

/** Main-thread state machine that isolates async getStats callbacks by media session and epoch. */
internal class MediaStatsRequestGate {
    private var nextSessionId = 0L
    private var nextEpoch = 0L
    private var nextRequestId = 0L
    private var session: MediaStatsSession? = null
    private var epoch = 0L
    private var transportReady = false
    private var closed = false
    private var inFlight: MediaStatsRequestToken? = null

    fun openSession(callId: String): MediaStatsSession {
        val opened = MediaStatsSession(callId, ++nextSessionId)
        session = opened
        epoch = ++nextEpoch
        transportReady = false
        closed = false
        inFlight = null
        return opened
    }

    fun onConnection(
        candidate: MediaStatsSession,
        state: MediaConnectionState,
    ): MediaConnectionEpoch? {
        if (session != candidate || closed) return null
        val wasReady = transportReady
        when (state) {
            MediaConnectionState.Connected -> transportReady = true
            MediaConnectionState.Connecting,
            MediaConnectionState.Disconnected,
            MediaConnectionState.Failed,
            MediaConnectionState.Closed -> {
                if (transportReady) epoch = ++nextEpoch
                transportReady = false
                inFlight = null
                if (state == MediaConnectionState.Closed) closed = true
            }
        }
        return MediaConnectionEpoch(
            epoch = epoch,
            transportReady = transportReady,
            becameReady = transportReady && !wasReady,
        )
    }

    fun begin(candidate: MediaStatsSession): MediaStatsRequestToken? {
        if (session != candidate || closed || !transportReady || inFlight != null) return null
        return MediaStatsRequestToken(
            callId = candidate.callId,
            sessionId = candidate.id,
            epoch = epoch,
            requestId = ++nextRequestId,
        ).also { inFlight = it }
    }

    fun complete(token: MediaStatsRequestToken): Boolean {
        if (inFlight != token) return false
        inFlight = null
        val current = session
        return !closed && transportReady && current?.id == token.sessionId &&
            current.callId == token.callId && epoch == token.epoch
    }

    fun reset() {
        session = null
        transportReady = false
        closed = false
        inFlight = null
        epoch = ++nextEpoch
    }
}
