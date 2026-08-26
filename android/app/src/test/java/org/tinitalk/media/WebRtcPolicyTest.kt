package org.tinitalk.media

import org.junit.Assert.assertEquals
import org.junit.Test
import org.webrtc.PeerConnection

class WebRtcPolicyTest {
    @Test
    fun relayDebugOptionForcesRelayCandidatesOnly() {
        assertEquals(PeerConnection.IceTransportsType.RELAY, WebRtcPolicy.iceTransport(true))
        assertEquals(PeerConnection.IceTransportsType.ALL, WebRtcPolicy.iceTransport(false))
    }

    @Test
    fun audioTrackIsEnabledOnlyForActiveUnmutedCalls() {
        assertEquals(true, WebRtcPolicy.audioTrackEnabled(active = true, muted = false))
        assertEquals(false, WebRtcPolicy.audioTrackEnabled(active = true, muted = true))
        assertEquals(false, WebRtcPolicy.audioTrackEnabled(active = false, muted = false))
        assertEquals(false, WebRtcPolicy.audioTrackEnabled(active = false, muted = true))
    }
}
