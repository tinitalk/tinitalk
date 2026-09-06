package org.tinitalk.media

import java.util.Locale

/** Diagnostic-only snapshots; they do not affect media adaptation or call health. */
internal class VideoDiagnosticsCollector {
    private var previousSamples: Map<String, CallStatsSample> = emptyMap()
    private var previousAtMillis: Long? = null

    fun collect(samples: Map<String, CallStatsSample>, nowMillis: Long): List<String> {
        val video = samples.filterValues {
            it.members["kind"] == "video" && it.type in setOf("outbound-rtp", "inbound-rtp")
        }
        val previous = previousSamples
        val elapsed = previousAtMillis?.let { nowMillis.toDouble() - it.toDouble() }?.takeIf { it > 0.0 }
        previousSamples = video
        previousAtMillis = nowMillis

        return video.map { (id, sample) ->
            val current = sample.members
            val before = previous[id]?.takeIf {
                elapsed != null && it.type == sample.type &&
                    it.members["ssrc"] == current["ssrc"] &&
                    it.members["codecId"] == current["codecId"] &&
                    it.members["mediaSourceId"] == current["mediaSourceId"]
            }?.members
            val sending = sample.type == "outbound-rtp"
            val codec = samples[current["codecId"] as? String]?.members
            val source = samples[current["mediaSourceId"] as? String]?.members

            // A missing baseline or a reset counter is unknown, not a zero-delay sample.
            fun delta(key: String): Double? {
                val end = number(current[key]) ?: return null
                val start = number(before?.get(key)) ?: return null
                return (end - start).takeIf { it >= 0.0 }
            }
            fun rate(key: String): Double? = elapsed?.let { delta(key)?.times(1_000.0 / it) }
            fun averageMs(total: String, count: String): Double? {
                val seconds = delta(total) ?: return null
                val events = delta(count)?.takeIf { it > 0.0 } ?: return null
                return seconds * 1_000.0 / events
            }

            buildString {
                fun field(name: String, value: String) { append(" $name=$value") }
                fun metric(name: String, value: Double?) {
                    field(name, value?.takeIf { it.isFinite() && it >= 0.0 }
                        ?.let { String.format(Locale.US, "%.2f", it) } ?: "na")
                }
                append("direction=${if (sending) "send" else "receive"}")
                field("codec", label(codec?.get("mimeType")))
                field("implementation", label(current[if (sending) "encoderImplementation" else "decoderImplementation"]))
                field("power_efficient", (current[if (sending) "powerEfficientEncoder" else "powerEfficientDecoder"] as? Boolean)
                    ?.toString() ?: "na")
                metric("width", number(current["frameWidth"]))
                metric("height", number(current["frameHeight"]))
                if (sending) {
                    field("active", (current["active"] as? Boolean)?.toString() ?: "na")
                    field("limitation", (current["qualityLimitationReason"] as? String)
                        ?.takeIf { it in setOf("none", "cpu", "bandwidth", "other") } ?: "na")
                    metric("capture_fps", number(source?.get("framesPerSecond")))
                    metric("encoded_fps", rate("framesEncoded"))
                    metric("sent_fps", rate("framesSent"))
                    metric("bitrate_kbps", rate("bytesSent")?.times(8.0 / 1_000.0))
                    metric("encode_ms", averageMs("totalEncodeTime", "framesEncoded"))
                    // Per-packet time in the sender's queue, not network transit time.
                    metric("send_queue_ms", averageMs("totalPacketSendDelay", "packetsSent"))
                } else {
                    metric("received_fps", rate("framesReceived"))
                    metric("decoded_fps", rate("framesDecoded"))
                    metric("bitrate_kbps", rate("bytesReceived")?.times(8.0 / 1_000.0))
                    metric("dropped_frames", delta("framesDropped"))
                    metric("jitter_ms", number(current["jitter"])?.times(1_000.0))
                    metric("decode_ms", averageMs("totalDecodeTime", "framesDecoded"))
                    metric("buffer_ms", averageMs("jitterBufferDelay", "jitterBufferEmittedCount"))
                    metric("buffer_target_ms", averageMs("jitterBufferTargetDelay", "jitterBufferEmittedCount"))
                    // Processing includes buffering and decoding; these times must not be added together.
                    metric("processing_ms", averageMs("totalProcessingDelay", "framesDecoded"))
                }
            }
        }
    }

    private fun number(value: Any?): Double? =
        (value as? Number)?.toDouble()?.takeIf { it.isFinite() && it >= 0.0 }

    // Only codec/implementation labels, never complete stats maps, SDP, addresses or track identifiers.
    private fun label(value: Any?): String =
        (value as? String)?.replace(' ', '_')?.takeIf { Label.matches(it) } ?: "na"

    private companion object {
        val Label = Regex("[A-Za-z0-9_./:(),+-]{1,100}")
    }
}
