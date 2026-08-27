package org.tinitalk.media

import org.webrtc.PeerConnection

object WebRtcPolicy {
    const val useLowLatencyAudio = true
    val continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY

    fun iceTransport(forceRelay: Boolean): PeerConnection.IceTransportsType =
        if (forceRelay) PeerConnection.IceTransportsType.RELAY else PeerConnection.IceTransportsType.ALL

    fun audioTrackEnabled(active: Boolean, muted: Boolean): Boolean = active && !muted

    fun microphoneMuted(active: Boolean, muted: Boolean): Boolean = !active || muted

    fun speakerMuted(active: Boolean): Boolean = !active
}
