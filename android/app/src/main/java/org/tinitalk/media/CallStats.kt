package org.tinitalk.media

import java.util.Locale

data class CallStats(
    val rttMs: Long = 0,
    val jitterMs: Long = 0,
    val packetLossPercent: Double = 0.0,
    val jitterBufferDelayMs: Long = 0,
    val jitterBufferTargetDelayMs: Long = 0,
    val concealedSamplesPercent: Double = 0.0,
    val packetsDiscarded: Long = 0,
    val concealmentEvents: Long = 0,
    val fecPacketsReceived: Long = 0,
    val bitrateKbps: Long = 0,
    val localCandidateType: String = "",
    val remoteCandidateType: String = "",
    val transportProtocol: String = "",
    val relayProtocol: String = "",
)

data class CallStatsSample(
    val type: String,
    val members: Map<String, Any>,
)

class CallStatsCollector {
    private var previousBytesSent: Long? = null
    private var previousAtMillis: Long? = null
    private var previousInbound: InboundCounters? = null

    @Synchronized
    fun collect(samples: Map<String, CallStatsSample>, nowMillis: Long): CallStats {
        val transport = samples.values.firstOrNull {
            it.type == "transport" && it.members["selectedCandidatePairId"] is String
        }
        val selectedId = transport?.members?.get("selectedCandidatePairId") as? String
        val pair = selectedId?.let(samples::get) ?: samples.values.firstOrNull {
            it.type == "candidate-pair" &&
                it.members["nominated"] == true &&
                it.members["state"] == "succeeded"
        }
        val inboundEntry = samples.entries
            .filter { it.value.type == "inbound-rtp" && it.value.members["kind"] == "audio" }
            .maxByOrNull { nonNegativeCounter(it.value.members["packetsReceived"]) ?: 0L }
        val inbound = inboundEntry?.value
        val outbound = samples.values
            .filter { it.type == "outbound-rtp" && it.members["kind"] == "audio" }
            .maxByOrNull { nonNegativeCounter(it.members["bytesSent"]) ?: 0L }
        val bytesSent = nonNegativeCounter(outbound?.members?.get("bytesSent"))
        val bitrate = bitrateKbps(bytesSent, nowMillis)
        val inboundInterval = inboundEntry?.let { intervalCounters(it.key, it.value) } ?: InboundCounters()
        val localCandidate = candidate(pair, "localCandidateId", samples)
        val remoteCandidate = candidate(pair, "remoteCandidateId", samples)
        val relayCandidate = listOfNotNull(localCandidate, remoteCandidate).firstOrNull {
            candidateValue(it, "candidateType", CandidateTypes) == "relay"
        }

        return CallStats(
            rttMs = milliseconds(pair?.members?.get("currentRoundTripTime")),
            jitterMs = milliseconds(inbound?.members?.get("jitter")),
            packetLossPercent = percentage(inboundInterval.lost, inboundInterval.received),
            jitterBufferDelayMs = averageDelayMillis(inboundInterval.jitterBufferDelay, inboundInterval.emitted),
            jitterBufferTargetDelayMs = averageDelayMillis(inboundInterval.jitterBufferTargetDelay, inboundInterval.emitted),
            concealedSamplesPercent = ratioPercent(
                (inboundInterval.concealedSamples - inboundInterval.silentConcealedSamples).coerceAtLeast(0L),
                inboundInterval.totalSamples,
            ),
            packetsDiscarded = inboundInterval.packetsDiscarded,
            concealmentEvents = inboundInterval.concealmentEvents,
            fecPacketsReceived = inboundInterval.fecPacketsReceived,
            bitrateKbps = bitrate,
            localCandidateType = candidateValue(localCandidate, "candidateType", CandidateTypes),
            remoteCandidateType = candidateValue(remoteCandidate, "candidateType", CandidateTypes),
            transportProtocol = candidateValue(localCandidate, "protocol", TransportProtocols),
            relayProtocol = candidateValue(relayCandidate, "relayProtocol", RelayProtocols),
        )
    }

    private fun intervalCounters(id: String, sample: CallStatsSample): InboundCounters {
        val current = InboundCounters(
            id = id,
            lost = signedCounter(sample.members["packetsLost"]) ?: 0L,
            received = nonNegativeCounter(sample.members["packetsReceived"]) ?: 0L,
            jitterBufferDelay = finiteNonNegative(sample.members["jitterBufferDelay"]) ?: 0.0,
            jitterBufferTargetDelay = finiteNonNegative(sample.members["jitterBufferTargetDelay"]) ?: 0.0,
            emitted = nonNegativeCounter(sample.members["jitterBufferEmittedCount"]) ?: 0L,
            totalSamples = nonNegativeCounter(sample.members["totalSamplesReceived"]) ?: 0L,
            concealedSamples = nonNegativeCounter(sample.members["concealedSamples"]) ?: 0L,
            silentConcealedSamples = nonNegativeCounter(sample.members["silentConcealedSamples"]) ?: 0L,
            packetsDiscarded = nonNegativeCounter(sample.members["packetsDiscarded"]) ?: 0L,
            concealmentEvents = nonNegativeCounter(sample.members["concealmentEvents"]) ?: 0L,
            fecPacketsReceived = nonNegativeCounter(sample.members["fecPacketsReceived"]) ?: 0L,
        )
        val previous = previousInbound?.takeIf { it.id == id }
        previousInbound = current
        return InboundCounters(
            id = id,
            lost = lossDelta(current.lost, previous?.lost),
            received = counterDelta(current.received, previous?.received),
            jitterBufferDelay = counterDelta(current.jitterBufferDelay, previous?.jitterBufferDelay),
            jitterBufferTargetDelay = counterDelta(current.jitterBufferTargetDelay, previous?.jitterBufferTargetDelay),
            emitted = counterDelta(current.emitted, previous?.emitted),
            totalSamples = counterDelta(current.totalSamples, previous?.totalSamples),
            concealedSamples = counterDelta(current.concealedSamples, previous?.concealedSamples),
            silentConcealedSamples = counterDelta(current.silentConcealedSamples, previous?.silentConcealedSamples),
            packetsDiscarded = counterDelta(current.packetsDiscarded, previous?.packetsDiscarded),
            concealmentEvents = counterDelta(current.concealmentEvents, previous?.concealmentEvents),
            fecPacketsReceived = counterDelta(current.fecPacketsReceived, previous?.fecPacketsReceived),
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

    private fun percentage(part: Long, rest: Long): Double {
        val total = part.toDouble() + rest.toDouble()
        if (!total.isFinite() || total <= 0.0) return 0.0
        return (part.toDouble() * 100.0 / total).takeIf(Double::isFinite)?.coerceIn(0.0, 100.0) ?: 0.0
    }

    private fun ratioPercent(part: Long, total: Long): Double {
        if (total <= 0L) return 0.0
        return (part.toDouble() * 100.0 / total.toDouble())
            .takeIf(Double::isFinite)
            ?.coerceIn(0.0, 100.0)
            ?: 0.0
    }

    private fun averageDelayMillis(delaySeconds: Double, emitted: Long): Long {
        if (emitted <= 0L) return 0L
        val value = delaySeconds * 1_000.0 / emitted.toDouble()
        return value.takeIf { it.isFinite() && it >= 0.0 && it < Long.MAX_VALUE.toDouble() }?.toLong() ?: 0L
    }

    private fun counterDelta(current: Long, previous: Long?): Long =
        if (previous == null || current < previous) current else current - previous

    private fun lossDelta(current: Long, previous: Long?): Long {
        if (previous == null) return current.coerceAtLeast(0L)
        if (current <= previous) return 0L
        return runCatching { Math.subtractExact(current, previous) }.getOrDefault(Long.MAX_VALUE)
    }

    private fun counterDelta(current: Double, previous: Double?): Double =
        if (previous == null || current < previous) current else current - previous

    private fun nonNegativeCounter(value: Any?): Long? = when (value) {
        is Byte, is Short, is Int, is Long -> value.toLong().takeIf { it >= 0L }
        else -> finiteNonNegative(value)
            ?.takeIf { it < Long.MAX_VALUE.toDouble() }
            ?.toLong()
    }

    private fun signedCounter(value: Any?): Long? = when (value) {
        is Byte, is Short, is Int, is Long -> value.toLong()
        is Number -> value.toDouble()
            .takeIf { it.isFinite() && it >= Long.MIN_VALUE.toDouble() && it <= Long.MAX_VALUE.toDouble() }
            ?.toLong()
        else -> null
    }

    private fun finiteNonNegative(value: Any?): Double? =
        (value as? Number)
            ?.toDouble()
            ?.takeIf { it.isFinite() && it >= 0.0 }

    private fun candidate(
        pair: CallStatsSample?,
        idField: String,
        samples: Map<String, CallStatsSample>,
    ): CallStatsSample? =
        (pair?.members?.get(idField) as? String)
            ?.let(samples::get)

    private fun candidateValue(candidate: CallStatsSample?, field: String, allowed: Set<String>): String =
        candidate
            ?.members
            ?.get(field)
            ?.let { it as? String }
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it in allowed }
            .orEmpty()

    private companion object {
        val CandidateTypes = setOf("host", "srflx", "prflx", "relay")
        val TransportProtocols = setOf("udp", "tcp")
        val RelayProtocols = setOf("udp", "tcp", "tls")
    }

    private data class InboundCounters(
        val id: String = "",
        val lost: Long = 0,
        val received: Long = 0,
        val jitterBufferDelay: Double = 0.0,
        val jitterBufferTargetDelay: Double = 0.0,
        val emitted: Long = 0,
        val totalSamples: Long = 0,
        val concealedSamples: Long = 0,
        val silentConcealedSamples: Long = 0,
        val packetsDiscarded: Long = 0,
        val concealmentEvents: Long = 0,
        val fecPacketsReceived: Long = 0,
    )
}
