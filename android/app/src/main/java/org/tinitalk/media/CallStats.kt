package org.tinitalk.media

data class CallStats(
    val rttMs: Long = 0,
    val jitterMs: Long = 0,
    val packetLossPercent: Double = 0.0,
    val bitrateKbps: Long = 0,
    val localCandidateType: String = "",
    val remoteCandidateType: String = "",
)
