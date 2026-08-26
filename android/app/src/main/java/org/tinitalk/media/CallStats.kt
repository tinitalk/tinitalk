package org.tinitalk.media

import java.util.Locale

data class CallStats(
    val rttMs: Long = 0,
    val jitterMs: Long = 0,
    val packetLossPercent: Double = 0.0,
    val bitrateKbps: Long = 0,
    val localCandidateType: String = "",
    val remoteCandidateType: String = "",
)

data class CallStatsSample(
    val type: String,
    val members: Map<String, Any>,
)

class CallStatsCollector {
    private var previousBytesSent: Long? = null
    private var previousAtMillis: Long? = null

    @Synchronized
    fun collect(samples: Map<String, CallStatsSample>, nowMillis: Long): CallStats {
        val transport = samples.values.firstOrNull { it.type == "transport" }
        val selectedId = transport?.members?.get("selectedCandidatePairId") as? String
        val pair = selectedId?.let(samples::get) ?: samples.values.firstOrNull {
            it.type == "candidate-pair" &&
                it.members["nominated"] == true &&
                it.members["state"] == "succeeded"
        }
        val inbound = samples.values.firstOrNull { it.type == "inbound-rtp" && it.members["kind"] == "audio" }
        val outbound = samples.values.firstOrNull { it.type == "outbound-rtp" && it.members["kind"] == "audio" }
        val bytesSent = nonNegativeCounter(outbound?.members?.get("bytesSent"))
        val bitrate = bitrateKbps(bytesSent, nowMillis)
        val lost = nonNegativeCounter(inbound?.members?.get("packetsLost")) ?: 0L
        val received = nonNegativeCounter(inbound?.members?.get("packetsReceived")) ?: 0L

        return CallStats(
            rttMs = milliseconds(pair?.members?.get("currentRoundTripTime")),
            jitterMs = milliseconds(inbound?.members?.get("jitter")),
            packetLossPercent = packetLossPercent(lost, received),
            bitrateKbps = bitrate,
            localCandidateType = candidateType(pair, "localCandidateId", samples),
            remoteCandidateType = candidateType(pair, "remoteCandidateId", samples),
        )
    }

    private fun bitrateKbps(bytesSent: Long?, nowMillis: Long): Long {
        if (bytesSent == null) return 0L
        val previousBytes = previousBytesSent
        val previousAt = previousAtMillis
        val bitrate = if (previousBytes != null && previousAt != null && bytesSent >= previousBytes) {
            val elapsedMillis = nowMillis.toDouble() - previousAt.toDouble()
            val bitsPerSecond = (bytesSent - previousBytes).toDouble() * 8.0 / elapsedMillis
            if (elapsedMillis > 0.0 && bitsPerSecond.isFinite() && bitsPerSecond >= 0.0 && bitsPerSecond < Long.MAX_VALUE.toDouble()) {
                bitsPerSecond.toLong()
            } else {
                0L
            }
        } else {
            0L
        }
        previousBytesSent = bytesSent
        previousAtMillis = nowMillis
        return bitrate
    }

    private fun milliseconds(value: Any?): Long {
        val seconds = finiteNonNegative(value) ?: return 0L
        val milliseconds = seconds * 1_000.0
        return if (milliseconds.isFinite() && milliseconds < Long.MAX_VALUE.toDouble()) milliseconds.toLong() else 0L
    }

    private fun packetLossPercent(lost: Long, received: Long): Double {
        val total = lost.toDouble() + received.toDouble()
        if (!total.isFinite() || total <= 0.0) return 0.0
        return (lost.toDouble() * 100.0 / total).takeIf(Double::isFinite)?.coerceIn(0.0, 100.0) ?: 0.0
    }

    private fun nonNegativeCounter(value: Any?): Long? = when (value) {
        is Byte, is Short, is Int, is Long -> value.toLong().takeIf { it >= 0L }
        else -> finiteNonNegative(value)
            ?.takeIf { it < Long.MAX_VALUE.toDouble() }
            ?.toLong()
    }

    private fun finiteNonNegative(value: Any?): Double? =
        (value as? Number)
            ?.toDouble()
            ?.takeIf { it.isFinite() && it >= 0.0 }

    private fun candidateType(
        pair: CallStatsSample?,
        idField: String,
        samples: Map<String, CallStatsSample>,
    ): String =
        (pair?.members?.get(idField) as? String)
            ?.let(samples::get)
            ?.members
            ?.get("candidateType")
            ?.let { it as? String }
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it in CandidateTypes }
            .orEmpty()

    private companion object {
        val CandidateTypes = setOf("host", "srflx", "prflx", "relay")
    }
}
