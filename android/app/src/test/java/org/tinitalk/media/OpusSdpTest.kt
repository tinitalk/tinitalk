package org.tinitalk.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpusSdpTest {
    @Test
    fun enablesInBandFecAndDisablesDtxForOpus() {
        val sdp = """
            m=audio 9 UDP/TLS/RTP/SAVPF 111
            a=rtpmap:111 opus/48000/2
            a=fmtp:111 minptime=10;usedtx=1
        """.trimIndent()

        val updated = OpusSdp.enableNetworkResilience(sdp)

        assertTrue(updated.contains("useinbandfec=1"))
        assertTrue(updated.contains("minptime=10"))
        assertFalse(updated.contains("usedtx="))
    }
}
