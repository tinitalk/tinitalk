package org.tinitalk.media

import org.webrtc.PeerConnection
import org.webrtc.RtpParameters

object WebRtcPolicy {
    const val useLowLatencyAudio = true
    const val videoCaptureWidth = 1920
    const val videoCaptureHeight = 1080
    const val videoCaptureFps = 30
    val continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
    val videoDegradationPreference = RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE

    fun configureConnection(configuration: PeerConnection.RTCConfiguration, forceRelay: Boolean) {
        configuration.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        configuration.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
        configuration.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
        configuration.iceTransportsType = iceTransport(forceRelay)
        configuration.continualGatheringPolicy = continualGatheringPolicy
        configuration.audioJitterBufferFastAccelerate = true
    }

    fun configureAudioEncodings(encodings: List<RtpParameters.Encoding>) {
        encodings.forEach { it.adaptiveAudioPacketTime = true }
    }

    fun configureVideoSender(
        encodings: List<RtpParameters.Encoding>,
        commit: () -> Boolean,
    ): Boolean {
        val encoding = encodings.singleOrNull() ?: return false
        encoding.maxBitrateBps = VideoMaxBitrateBps
        encoding.maxFramerate = null
        encoding.scaleResolutionDownBy = null
        return runCatching(commit).getOrDefault(false)
    }

    fun iceTransport(forceRelay: Boolean): PeerConnection.IceTransportsType =
        if (forceRelay) PeerConnection.IceTransportsType.RELAY else PeerConnection.IceTransportsType.ALL

    fun audioTrackEnabled(active: Boolean, muted: Boolean): Boolean = active && !muted

    fun microphoneMuted(active: Boolean, muted: Boolean): Boolean = !active || muted

    fun speakerMuted(active: Boolean): Boolean = !active

    private const val VideoMaxBitrateBps = 4_000_000
}
