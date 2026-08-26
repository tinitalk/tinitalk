package org.tinitalk.telecom

import org.tinitalk.media.CallStats
import org.junit.Assert.assertEquals
import org.junit.Test

class CallDiagnosticsTest {
    @Test
    fun formatsOneSecondAudioQualitySnapshot() {
        val stats = CallStats(
            rttMs = 120,
            jitterMs = 18,
            packetLossPercent = 2.25,
            jitterBufferDelayMs = 100,
            jitterBufferTargetDelayMs = 80,
            concealedSamplesPercent = 10.5,
            packetsDiscarded = 3,
            concealmentEvents = 2,
            fecPacketsReceived = 5,
            bitrateKbps = 32,
            localCandidateType = "relay",
            remoteCandidateType = "srflx",
            transportProtocol = "udp",
            relayProtocol = "udp",
        )

        assertEquals(1_000L, CallDiagnostics.IntervalMillis)
        assertEquals(
            "rtt_ms=120 jitter_ms=18 loss_percent=2.25 jitter_buffer_ms=100 " +
                "jitter_buffer_target_ms=80 concealed_percent=10.50 packets_discarded=3 " +
                "concealment_events=2 fec_packets_received=5 bitrate_kbps=32 " +
                "local_candidate_type=relay remote_candidate_type=srflx transport_protocol=udp relay_protocol=udp",
            CallDiagnostics.format(stats),
        )
    }
}
