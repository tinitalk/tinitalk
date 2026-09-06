package org.tinitalk.media

import org.junit.Assert.assertEquals
import org.junit.Test

class CallStatsTest {
    @Test
    fun extractsSelectedRelayPairAndAudioQualityWithoutAddresses() {
        val collector = CallStatsCollector()

        val stats = collector.collect(
            linkedMapOf(
                "transport" to sample("transport", "selectedCandidatePairId" to "pair"),
                "pair" to sample(
                    "candidate-pair",
                    "localCandidateId" to "local",
                    "remoteCandidateId" to "remote",
                    "currentRoundTripTime" to 0.120,
                ),
                "local" to sample(
                    "local-candidate",
                    "candidateType" to "relay",
                    "protocol" to "udp",
                    "relayProtocol" to "udp",
                    "address" to "192.0.2.1",
                    "port" to 3478,
                ),
                "remote" to sample("remote-candidate", "candidateType" to "srflx", "address" to "198.51.100.10", "port" to 49160),
                "video-in" to sample("inbound-rtp", "kind" to "video", "jitter" to 9.0, "packetsLost" to 99, "packetsReceived" to 1),
                "audio-in" to sample(
                    "inbound-rtp",
                    "kind" to "audio",
                    "jitter" to 0.018,
                    "packetsLost" to 2,
                    "packetsReceived" to 98,
                    "jitterBufferDelay" to 4_800.0,
                    "jitterBufferTargetDelay" to 3_840.0,
                    "jitterBufferEmittedCount" to 48_000,
                    "totalSamplesReceived" to 48_000,
                    "concealedSamples" to 4_800,
                    "packetsDiscarded" to 3,
                    "concealmentEvents" to 2,
                    "fecPacketsReceived" to 5,
                ),
            ),
            nowMillis = 2_000,
        )

        assertEquals("relay", stats.localCandidateType)
        assertEquals("srflx", stats.remoteCandidateType)
        assertEquals(120L, stats.rttMs)
        assertEquals(18L, stats.jitterMs)
        assertEquals(2.0, stats.packetLossPercent, 0.01)
        assertEquals(100L, stats.jitterBufferDelayMs)
        assertEquals(80L, stats.jitterBufferTargetDelayMs)
        assertEquals(10.0, stats.concealedSamplesPercent, 0.01)
        assertEquals(3L, stats.packetsDiscarded)
        assertEquals(2L, stats.concealmentEvents)
        assertEquals(5L, stats.fecPacketsReceived)
        assertEquals("udp", stats.transportProtocol)
        assertEquals("udp", stats.relayProtocol)
    }

    @Test
    fun reportsAudioQualityForTheLatestInterval() {
        val collector = CallStatsCollector()
        collector.collect(
            audioInbound(
                lost = 2,
                received = 98,
                jitterBufferDelay = 4_800.0,
                jitterBufferTargetDelay = 3_840.0,
                emitted = 48_000,
                totalSamples = 48_000,
                concealedSamples = 4_800,
                packetsDiscarded = 3,
                concealmentEvents = 2,
                fecPacketsReceived = 5,
            ),
            nowMillis = 1_000,
        )

        val stats = collector.collect(
            audioInbound(
                lost = 3,
                received = 197,
                jitterBufferDelay = 9_840.0,
                jitterBufferTargetDelay = 7_920.0,
                emitted = 96_000,
                totalSamples = 96_000,
                concealedSamples = 7_200,
                packetsDiscarded = 7,
                concealmentEvents = 5,
                fecPacketsReceived = 11,
            ),
            nowMillis = 2_000,
        )

        assertEquals(1.0, stats.packetLossPercent, 0.01)
        assertEquals(105L, stats.jitterBufferDelayMs)
        assertEquals(85L, stats.jitterBufferTargetDelayMs)
        assertEquals(5.0, stats.concealedSamplesPercent, 0.01)
        assertEquals(4L, stats.packetsDiscarded)
        assertEquals(3L, stats.concealmentEvents)
        assertEquals(6L, stats.fecPacketsReceived)
    }

    @Test
    fun excludesSilentSamplesFromConcealedAudioPercentage() {
        val collector = CallStatsCollector()
        collector.collect(
            audioInbound(
                lost = 0,
                received = 100,
                jitterBufferDelay = 0.0,
                jitterBufferTargetDelay = 0.0,
                emitted = 48_000,
                totalSamples = 48_000,
                concealedSamples = 0,
                silentConcealedSamples = 0,
                packetsDiscarded = 0,
                concealmentEvents = 0,
                fecPacketsReceived = 0,
            ),
            nowMillis = 1_000,
        )

        val stats = collector.collect(
            audioInbound(
                lost = 0,
                received = 200,
                jitterBufferDelay = 0.0,
                jitterBufferTargetDelay = 0.0,
                emitted = 96_000,
                totalSamples = 96_000,
                concealedSamples = 4_800,
                silentConcealedSamples = 3_600,
                packetsDiscarded = 0,
                concealmentEvents = 1,
                fecPacketsReceived = 0,
            ),
            nowMillis = 2_000,
        )

        assertEquals(2.5, stats.concealedSamplesPercent, 0.01)
    }

    @Test
    fun selectsTheActiveAudioStreamInsteadOfAStaleOne() {
        val stats = CallStatsCollector().collect(
            linkedMapOf(
                "stale-audio" to sample(
                    "inbound-rtp",
                    "kind" to "audio",
                    "packetsReceived" to 1,
                    "packetsLost" to 99,
                    "jitter" to 1.0,
                ),
                "active-audio" to sample(
                    "inbound-rtp",
                    "kind" to "audio",
                    "packetsReceived" to 999,
                    "packetsLost" to 1,
                    "jitter" to 0.010,
                ),
            ),
            nowMillis = 1_000,
        )

        assertEquals(10L, stats.jitterMs)
        assertEquals(0.1, stats.packetLossPercent, 0.01)
    }

    @Test
    fun fallsBackToNominatedSucceededCandidatePair() {
        val stats = CallStatsCollector().collect(
            linkedMapOf(
                "pair" to sample("candidate-pair", "nominated" to true, "state" to "succeeded", "currentRoundTripTime" to 0.050),
                "local" to sample("local-candidate", "candidateType" to "host"),
                "remote" to sample("remote-candidate", "candidateType" to "relay"),
            ).mapValues { (id, value) ->
                if (id == "pair") sample(
                    "candidate-pair",
                    "nominated" to true,
                    "state" to "succeeded",
                    "currentRoundTripTime" to 0.050,
                    "localCandidateId" to "local",
                    "remoteCandidateId" to "remote",
                ) else value
            },
            nowMillis = 2_000,
        )

        assertEquals(50L, stats.rttMs)
        assertEquals("host", stats.localCandidateType)
        assertEquals("relay", stats.remoteCandidateType)
    }

    @Test
    fun calculatesOutboundBitrateFromConsecutiveByteCounters() {
        val collector = CallStatsCollector()

        collector.collect(outboundBytes(1_000), nowMillis = 1_000)
        val stats = collector.collect(outboundBytes(11_000), nowMillis = 2_000)

        assertEquals(80L, stats.bitrateKbps)
    }

    @Test
    fun clampsCounterRollbackToZeroBitrate() {
        val collector = CallStatsCollector()

        collector.collect(outboundBytes(11_000), nowMillis = 1_000)
        val stats = collector.collect(outboundBytes(1_000), nowMillis = 2_000)

        assertEquals(0L, stats.bitrateKbps)
    }

    @Test
    fun allowListsCandidateTypesAndDropsMaliciousValues() {
        val stats = CallStatsCollector().collect(
            linkedMapOf(
                "transport" to sample("transport", "selectedCandidatePairId" to "pair"),
                "pair" to sample("candidate-pair", "localCandidateId" to "local", "remoteCandidateId" to "remote"),
                "local" to sample(
                    "local-candidate",
                    "candidateType" to "RELAY\ncredential=secret",
                    "address" to "192.0.2.1",
                    "credential" to "secret",
                ),
                "remote" to sample("remote-candidate", "candidateType" to "SrFlX"),
            ),
            nowMillis = 1_000,
        )

        assertEquals("", stats.localCandidateType)
        assertEquals("srflx", stats.remoteCandidateType)
    }

    @Test
    fun rejectsNegativeAndNonFiniteMetricsAndCounters() {
        val stats = CallStatsCollector().collect(
            mapOf(
                "pair" to sample("candidate-pair", "currentRoundTripTime" to Double.NaN),
                "audio-in" to sample(
                    "inbound-rtp",
                    "kind" to "audio",
                    "jitter" to Double.NEGATIVE_INFINITY,
                    "packetsLost" to -1,
                    "packetsReceived" to Double.POSITIVE_INFINITY,
                ),
            ),
            nowMillis = 1_000,
        )

        assertEquals(0L, stats.rttMs)
        assertEquals(0L, stats.jitterMs)
        assertEquals(0.0, stats.packetLossPercent, 0.0)
    }

    @Test
    fun rejectsNegativeInfiniteAndOutOfRangeTimingValues() {
        val negative = CallStatsCollector().collect(
            mapOf(
                "pair" to sample("candidate-pair", "currentRoundTripTime" to -0.1),
                "audio-in" to sample("inbound-rtp", "kind" to "audio", "jitter" to -0.1),
            ),
            nowMillis = 1_000,
        )
        val nonFinite = CallStatsCollector().collect(
            mapOf(
                "pair" to sample("candidate-pair", "currentRoundTripTime" to Double.POSITIVE_INFINITY),
                "audio-in" to sample("inbound-rtp", "kind" to "audio", "jitter" to Double.MAX_VALUE),
            ),
            nowMillis = 1_000,
        )

        assertEquals(0L, negative.rttMs)
        assertEquals(0L, negative.jitterMs)
        assertEquals(0L, nonFinite.rttMs)
        assertEquals(0L, nonFinite.jitterMs)
    }

    @Test
    fun calculatesLossWithoutCounterOverflow() {
        val stats = CallStatsCollector().collect(
            mapOf(
                "audio-in" to sample(
                    "inbound-rtp",
                    "kind" to "audio",
                    "packetsLost" to Long.MAX_VALUE,
                    "packetsReceived" to Long.MAX_VALUE,
                ),
            ),
            nowMillis = 1_000,
        )

        assertEquals(50.0, stats.packetLossPercent, 0.01)
    }

    @Test
    fun latePacketsReducingPacketsLostDoNotCreateAnIntervalLossSpike() {
        val collector = CallStatsCollector()
        collector.collect(
            mapOf(
                "audio-in" to sample(
                    "inbound-rtp",
                    "kind" to "audio",
                    "packetsLost" to 10,
                    "packetsReceived" to 90,
                ),
            ),
            nowMillis = 1_000,
        )

        val stats = collector.collect(
            mapOf(
                "audio-in" to sample(
                    "inbound-rtp",
                    "kind" to "audio",
                    "packetsLost" to 8,
                    "packetsReceived" to 112,
                ),
            ),
            nowMillis = 2_000,
        )

        assertEquals(0.0, stats.packetLossPercent, 0.0)
    }

    @Test
    fun rejectsInvalidAndOverflowingOutboundCounters() {
        val collector = CallStatsCollector()
        collector.collect(outboundBytes(0L), nowMillis = 1_000)

        val overflow = collector.collect(outboundBytes(Long.MAX_VALUE), nowMillis = 1_001)
        val negative = collector.collect(outboundBytes(-1L), nowMillis = 1_002)
        val nonFinite = collector.collect(outboundBytes(Double.NaN), nowMillis = 1_003)

        assertEquals(0L, overflow.bitrateKbps)
        assertEquals(0L, negative.bitrateKbps)
        assertEquals(0L, nonFinite.bitrateKbps)
    }

    @Test
    fun returnsZeroBitrateForEqualOrDecreasingSampleTimes() {
        val equalTime = CallStatsCollector()
        equalTime.collect(outboundBytes(1_000), nowMillis = 1_000)
        assertEquals(0L, equalTime.collect(outboundBytes(11_000), nowMillis = 1_000).bitrateKbps)

        val decreasingTime = CallStatsCollector()
        decreasingTime.collect(outboundBytes(1_000), nowMillis = 1_000)
        assertEquals(0L, decreasingTime.collect(outboundBytes(11_000), nowMillis = 999).bitrateKbps)
    }

    @Test
    fun missingStatsReturnZeroValuesWithoutThrowing() {
        assertEquals(CallStats(), CallStatsCollector().collect(emptyMap(), nowMillis = 1_000))
    }

    @Test
    fun reportsVideoRatesAndDelaysForTheLatestIntervalSeparatelyFromAudio() {
        val collector = CallStatsCollector()
        fun snapshots(step: Int) = mapOf(
            "codec" to sample("codec", "mimeType" to "video/VP8"),
            "source" to sample("media-source", "kind" to "video", "framesPerSecond" to 30.0),
            "send" to sample("outbound-rtp", "kind" to "video", "codecId" to "codec",
                "mediaSourceId" to "source", "encoderImplementation" to "libvpx", "powerEfficientEncoder" to false,
                "frameWidth" to 576, "frameHeight" to 1280, "qualityLimitationReason" to "bandwidth",
                "framesEncoded" to 100 + step * 20, "framesSent" to 100 + step * 18,
                "bytesSent" to 1_000_000L + step * 200_000L, "packetsSent" to 1000 + step * 100,
                "totalEncodeTime" to 10.0 + step * 0.4, "totalPacketSendDelay" to 100.0 + step * 5.0),
            "receive" to sample("inbound-rtp", "kind" to "video", "codecId" to "codec",
                "decoderImplementation" to "AndroidVideoDecoder", "framesReceived" to 100 + step * 19,
                "framesDecoded" to 100 + step * 18, "framesDropped" to 3 + step,
                "bytesReceived" to 1_000_000L + step * 150_000L, "totalDecodeTime" to 10.0 + step * 0.18,
                "jitterBufferDelay" to 100.0 + step * 7.2, "jitterBufferTargetDelay" to 100.0 + step * 5.4,
                "jitterBufferEmittedCount" to 100 + step * 18, "totalProcessingDelay" to 200.0 + step * 9.0),
        )
        collector.collect(snapshots(0), nowMillis = 1_000)
        val stats = collector.collect(snapshots(1), nowMillis = 2_000)
        val send = videoFields(stats.videoDiagnostics.first())
        val receive = videoFields(stats.videoDiagnostics.last())

        assertEquals("send", send["direction"])
        assertEquals("video/VP8", send["codec"])
        assertEquals("libvpx", send["implementation"])
        assertEquals("false", send["power_efficient"])
        assertEquals("bandwidth", send["limitation"])
        assertEquals("30.00", send["capture_fps"])
        assertEquals("20.00", send["encoded_fps"])
        assertEquals("18.00", send["sent_fps"])
        assertEquals("1600.00", send["bitrate_kbps"])
        assertEquals("20.00", send["encode_ms"])
        assertEquals("50.00", send["send_queue_ms"])
        assertEquals("receive", receive["direction"])
        assertEquals("19.00", receive["received_fps"])
        assertEquals("18.00", receive["decoded_fps"])
        assertEquals("1.00", receive["dropped_frames"])
        assertEquals("1200.00", receive["bitrate_kbps"])
        assertEquals("10.00", receive["decode_ms"])
        assertEquals("400.00", receive["buffer_ms"])
        assertEquals("300.00", receive["buffer_target_ms"])
        assertEquals("500.00", receive["processing_ms"])
        assertEquals(0L, stats.bitrateKbps)
        assertEquals(0L, stats.jitterBufferDelayMs)
    }

    @Test
    fun videoDiagnosticsDistinguishMissingSamplesIdleFramesAndCounterResets() {
        val collector = CallStatsCollector()
        fun snapshot(frames: Int, total: Double, id: String = "send", codec: String = "vp8") = mapOf(
            id to sample("outbound-rtp", "kind" to "video", "codecId" to codec,
                "framesEncoded" to frames, "totalEncodeTime" to total),
        )
        fun fields(samples: Map<String, CallStatsSample>, time: Long) =
            videoFields(collector.collect(samples, time).videoDiagnostics.single())

        assertEquals("na", fields(snapshot(10, 1.0), 1000)["encode_ms"])
        val idle = fields(snapshot(10, 1.0), 2000)
        assertEquals("0.00", idle["encoded_fps"])
        assertEquals("na", idle["encode_ms"])
        val reset = fields(snapshot(1, 0.1), 3000)
        assertEquals("na", reset["encoded_fps"])
        assertEquals("na", reset["encode_ms"])
        assertEquals("na", fields(snapshot(2, 0.2), 3000)["encoded_fps"])
        assertEquals("na", fields(snapshot(3, Double.NaN), 4000)["encode_ms"])
        assertEquals("na", fields(snapshot(10, 1.0, id = "new"), 5000)["encoded_fps"])
        assertEquals("na", fields(snapshot(20, 2.0, id = "new", codec = "h264"), 6000)["encoded_fps"])
        assertEquals(emptyList<String>(), collector.collect(emptyMap(), 7000).videoDiagnostics)
        assertEquals("na", fields(snapshot(30, 3.0, id = "new", codec = "h264"), 8000)["encoded_fps"])
    }

    @Test
    fun videoDiagnosticsOnlyLogSelectedMetricsAndSingleLineCodecLabels() {
        val stats = CallStatsCollector().collect(mapOf(
            "codec" to sample("codec", "mimeType" to "video/VP8\ncredential=secret", "sdpFmtpLine" to "secret"),
            "video" to sample("outbound-rtp", "kind" to "video", "codecId" to "codec",
                "encoderImplementation" to "libvpx\nsecret", "qualityLimitationReason" to "secret",
                "frameWidth" to Double.POSITIVE_INFINITY, "trackIdentifier" to "secret"),
        ), nowMillis = 1000)
        val line = stats.videoDiagnostics.single()
        val fields = videoFields(line)
        assertEquals("na", fields["codec"])
        assertEquals("na", fields["implementation"])
        assertEquals("na", fields["limitation"])
        assertEquals("na", fields["width"])
        assertEquals(false, line.contains("secret"))
        assertEquals(false, line.contains('\n'))
    }

    private fun videoFields(line: String): Map<String, String> =
        line.split(' ').associate { it.substringBefore('=') to it.substringAfter('=') }

    private fun outboundBytes(bytes: Number): Map<String, CallStatsSample> = mapOf(
        "video-out" to sample("outbound-rtp", "kind" to "video", "bytesSent" to 999_999L),
        "audio-out" to sample("outbound-rtp", "kind" to "audio", "bytesSent" to bytes),
    )

    private fun audioInbound(
        lost: Number,
        received: Number,
        jitterBufferDelay: Number,
        jitterBufferTargetDelay: Number,
        emitted: Number,
        totalSamples: Number,
        concealedSamples: Number,
        silentConcealedSamples: Number = 0,
        packetsDiscarded: Number,
        concealmentEvents: Number,
        fecPacketsReceived: Number,
    ): Map<String, CallStatsSample> = mapOf(
        "audio-in" to sample(
            "inbound-rtp",
            "kind" to "audio",
            "packetsLost" to lost,
            "packetsReceived" to received,
            "jitterBufferDelay" to jitterBufferDelay,
            "jitterBufferTargetDelay" to jitterBufferTargetDelay,
            "jitterBufferEmittedCount" to emitted,
            "totalSamplesReceived" to totalSamples,
            "concealedSamples" to concealedSamples,
            "silentConcealedSamples" to silentConcealedSamples,
            "packetsDiscarded" to packetsDiscarded,
            "concealmentEvents" to concealmentEvents,
            "fecPacketsReceived" to fecPacketsReceived,
        ),
    )

    private fun sample(type: String, vararg members: Pair<String, Any>): CallStatsSample =
        CallStatsSample(type, mapOf(*members))
}
