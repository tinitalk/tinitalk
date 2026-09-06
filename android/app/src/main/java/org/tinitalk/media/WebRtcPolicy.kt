package org.tinitalk.media

import org.webrtc.PeerConnection
import org.webrtc.RtpParameters

object WebRtcPolicy {
    const val useLowLatencyAudio = true
    const val videoCaptureWidth = 1920
    const val videoCaptureHeight = 1080
    const val videoCaptureFps = 30
    const val screenCaptureFps = 30
    // Keep WebRTC's screen probing defaults, but drain bursts sooner (300 ms instead of 2875 ms).
    // This is a soft queue target, not a hard latency limit or a packet-dropping threshold.
    const val screenSharingFieldTrials = "WebRTC-ProbingScreenshareBwe/1.0,300,80,40,-60,3/"
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
        encoding.bitratePriority = 1.0
        encoding.maxFramerate = null
        encoding.scaleResolutionDownBy = null
        return runCatching(commit).getOrDefault(false)
    }

    fun configureScreenSender(encodings: List<RtpParameters.Encoding>, commit: () -> Boolean): Boolean {
        val encoding = encodings.singleOrNull() ?: return false
        encoding.maxBitrateBps = 4_000_000
        // Screen updates yield bandwidth to the call's audio sender.
        encoding.bitratePriority = 0.5
        encoding.maxFramerate = screenCaptureFps
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
