package org.tinitalk.telecom

import org.tinitalk.media.CallStats
import java.util.Locale

internal object CallDiagnostics {
    const val IntervalMillis = 1_000L

    fun format(stats: CallStats): String = String.format(
        Locale.US,
        "rtt_ms=%d jitter_ms=%d loss_percent=%.2f jitter_buffer_ms=%d " +
            "jitter_buffer_target_ms=%d concealed_percent=%.2f packets_discarded=%d " +
            "concealment_events=%d fec_packets_received=%d bitrate_kbps=%d " +
            "local_candidate_type=%s remote_candidate_type=%s transport_protocol=%s relay_protocol=%s",
        stats.rttMs,
        stats.jitterMs,
        stats.packetLossPercent,
        stats.jitterBufferDelayMs,
        stats.jitterBufferTargetDelayMs,
        stats.concealedSamplesPercent,
        stats.packetsDiscarded,
        stats.concealmentEvents,
        stats.fecPacketsReceived,
        stats.bitrateKbps,
        stats.localCandidateType,
        stats.remoteCandidateType,
        stats.transportProtocol,
        stats.relayProtocol,
    )
}
