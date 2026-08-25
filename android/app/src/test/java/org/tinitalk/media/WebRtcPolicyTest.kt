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
}
