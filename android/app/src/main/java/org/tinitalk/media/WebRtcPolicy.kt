package org.tinitalk.media

import org.webrtc.PeerConnection
import org.webrtc.RtpParameters

object WebRtcPolicy {
    const val useLowLatencyAudio = true
    val continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY

    fun configureConnection(configuration: PeerConnection.RTCConfiguration, forceRelay: Boolean) {
        configuration.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        configuration.iceTransportsType = iceTransport(forceRelay)
        configuration.continualGatheringPolicy = continualGatheringPolicy
        configuration.audioJitterBufferFastAccelerate = true
    }

    fun configureAudioEncodings(encodings: List<RtpParameters.Encoding>) {
        encodings.forEach { it.adaptiveAudioPacketTime = true }
    }

    fun iceTransport(forceRelay: Boolean): PeerConnection.IceTransportsType =
        if (forceRelay) PeerConnection.IceTransportsType.RELAY else PeerConnection.IceTransportsType.ALL

    fun audioTrackEnabled(active: Boolean, muted: Boolean): Boolean = active && !muted

    fun microphoneMuted(active: Boolean, muted: Boolean): Boolean = !active || muted

    fun speakerMuted(active: Boolean): Boolean = !active
}
