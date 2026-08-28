package org.tinitalk.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.webrtc.PeerConnection
import org.webrtc.RtpParameters

class WebRtcPolicyTest {
    @Test
    fun lowLatencyAudioIsEnabled() {
        assertEquals(true, WebRtcPolicy.useLowLatencyAudio)
    }

    @Test
    fun relayDebugOptionForcesRelayCandidatesOnly() {
        assertEquals(PeerConnection.IceTransportsType.RELAY, WebRtcPolicy.iceTransport(true))
        assertEquals(PeerConnection.IceTransportsType.ALL, WebRtcPolicy.iceTransport(false))
    }

    @Test
    fun gathersIceCandidatesContinuallyAcrossNetworkChanges() {
        assertEquals(
            PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY,
            WebRtcPolicy.continualGatheringPolicy,
        )
    }

    @Test
    fun acceleratesRecoveryFromExcessAudioJitterDelay() {
        val configuration = PeerConnection.RTCConfiguration(emptyList())

        WebRtcPolicy.configureConnection(configuration, forceRelay = false)

        assertTrue(configuration.audioJitterBufferFastAccelerate)
    }

    @Test
    fun bundlesAudioAndVideoOnOneRtpTransport() {
        val configuration = PeerConnection.RTCConfiguration(emptyList()).apply {
            bundlePolicy = PeerConnection.BundlePolicy.MAXCOMPAT
        }

        WebRtcPolicy.configureConnection(configuration, forceRelay = false)

        assertEquals(PeerConnection.BundlePolicy.MAXBUNDLE, configuration.bundlePolicy)
    }

    @Test
    fun requiresRtcpMux() {
        val configuration = PeerConnection.RTCConfiguration(emptyList()).apply {
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.NEGOTIATE
        }

        WebRtcPolicy.configureConnection(configuration, forceRelay = false)

        assertEquals(PeerConnection.RtcpMuxPolicy.REQUIRE, configuration.rtcpMuxPolicy)
    }

    @Test
    fun adaptsAudioPacketTimeToNetworkConditions() {
        val encoding = RtpParameters.Encoding("audio", true, null)
        assertFalse(encoding.adaptiveAudioPacketTime)

        WebRtcPolicy.configureAudioEncodings(listOf(encoding))

        assertTrue(encoding.adaptiveAudioPacketTime)
    }

    @Test
    fun allowsAdaptiveHdVideoWithoutTouchingAudioPolicy() {
        val video = RtpParameters.Encoding("video", true, null)
        val audio = RtpParameters.Encoding("audio", true, null)
        var committed = false

        val applied = WebRtcPolicy.configureVideoSender(listOf(video)) {
            committed = true
            true
        }

        assertTrue(applied)
        assertTrue(committed)
        assertEquals(1920, WebRtcPolicy.videoCaptureWidth)
        assertEquals(1080, WebRtcPolicy.videoCaptureHeight)
        assertEquals(30, WebRtcPolicy.videoCaptureFps)
        assertEquals(4_000_000, video.maxBitrateBps)
        assertEquals(null, video.maxFramerate)
        assertEquals(null, video.scaleResolutionDownBy)
        assertEquals(
            RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE,
            WebRtcPolicy.videoDegradationPreference,
        )
        assertFalse(audio.adaptiveAudioPacketTime)
        assertEquals(null, audio.maxBitrateBps)
        assertEquals(null, audio.maxFramerate)
    }

    @Test
    fun rejectsMissingOrSimulcastVideoEncodingsWithoutCommitting() {
        var commits = 0
        val commit = {
            commits++
            true
        }

        assertFalse(WebRtcPolicy.configureVideoSender(emptyList(), commit))
        assertFalse(
            WebRtcPolicy.configureVideoSender(
                listOf(
                    RtpParameters.Encoding("low", true, null),
                    RtpParameters.Encoding("high", true, null),
                ),
                commit,
            ),
        )
        assertEquals(0, commits)
    }

    @Test
    fun videoSenderCommitFailureIsReportedToTheCameraBoundary() {
        val video = RtpParameters.Encoding("video", true, null)

        assertFalse(WebRtcPolicy.configureVideoSender(listOf(video)) { false })
    }

    @Test
    fun audioTrackIsEnabledOnlyForActiveUnmutedCalls() {
        assertEquals(true, WebRtcPolicy.audioTrackEnabled(active = true, muted = false))
        assertEquals(false, WebRtcPolicy.audioTrackEnabled(active = true, muted = true))
        assertEquals(false, WebRtcPolicy.audioTrackEnabled(active = false, muted = false))
        assertEquals(false, WebRtcPolicy.audioTrackEnabled(active = false, muted = true))
    }

    @Test
    fun inactiveCallMutesCaptureAndPlayout() {
        assertEquals(false, WebRtcPolicy.microphoneMuted(active = true, muted = false))
        assertEquals(true, WebRtcPolicy.microphoneMuted(active = true, muted = true))
        assertEquals(true, WebRtcPolicy.microphoneMuted(active = false, muted = false))
        assertEquals(false, WebRtcPolicy.speakerMuted(active = true))
        assertEquals(true, WebRtcPolicy.speakerMuted(active = false))
    }
}
