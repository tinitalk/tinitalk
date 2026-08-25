package org.tinitalk.call

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.tinitalk.data.signal.SignalEvent
import org.tinitalk.media.IceCandidateData
import org.tinitalk.media.IceServerData
import org.tinitalk.media.MediaSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundCallControllerTest {
    private val ids = object : EventIds {
        private var next = 1
        override fun nextEventId(): String = "00000000-0000-0000-0000-${(next++).toString().padStart(12, '0')}"
        override fun nextCallId(): String = "00000000-0000-0000-0000-000000000099"
        override fun nowMillis(): Long = 10L
    }

    @Test
    fun callerCreatesOfferAfterCallIsAccepted() {
        val signal = CapturingSignalClient()
        val media = FakeMediaSession(offer = "local-offer")
        val controller = ForegroundCallController(signal, { _, _, _ -> media }, ids)

        controller.onSignalEvent(activeSnapshot(), event("call.accept"))

        assertEquals("rtc.offer", signal.sent.single().type)
        assertEquals("local-offer", signal.sent.single().payload["sdp"].asString)
    }

    @Test
    fun calleeAnswersIncomingOffer() {
        val signal = CapturingSignalClient()
        val media = FakeMediaSession(answer = "local-answer")
        val controller = ForegroundCallController(signal, { _, _, _ -> media }, ids)
        val payload = JsonObject().apply { addProperty("sdp", "remote-offer") }

        controller.onSignalEvent(activeSnapshot(), event("rtc.offer", payload))

        assertEquals("remote-offer", media.acceptedOffer)
        assertEquals("rtc.answer", signal.sent.single().type)
        assertEquals("local-answer", signal.sent.single().payload["sdp"].asString)
    }

    @Test
    fun localIceCandidateIsSentThroughSignalClient() {
        val signal = CapturingSignalClient()
        lateinit var localIce: (IceCandidateData) -> Unit
        ForegroundCallController(signal, { _, _, callback ->
            localIce = callback
            FakeMediaSession()
        }, ids).onSignalEvent(activeSnapshot(), event("call.accept"))

        localIce(IceCandidateData("audio", 0, "candidate:1"))

        assertEquals("rtc.ice", signal.sent.last().type)
        assertEquals("candidate:1", signal.sent.last().payload["candidate"].asString)
    }

    @Test
    fun terminalEventClosesMediaSession() {
        val signal = CapturingSignalClient()
        val media = FakeMediaSession()
        val controller = ForegroundCallController(signal, { _, _, _ -> media }, ids)
        controller.onSignalEvent(activeSnapshot(), event("call.accept"))

        controller.onSignalEvent(CallSnapshot(CallPhase.Ended, callId, 2), event("call.end"))

        assertTrue(media.closed)
    }

    @Test
    fun usesIceServersFromRtcConfigWhenCreatingMediaSession() {
        var capturedServers = emptyList<IceServerData>()
        val controller = ForegroundCallController(CapturingSignalClient(), { _, servers, _ ->
            capturedServers = servers
            FakeMediaSession()
        }, ids)
        val config = JsonObject().apply {
            add("ice_servers", JsonArray().apply {
                add(JsonObject().apply {
                    add("urls", JsonArray().apply { add("turn:relay.example.com:3478?transport=udp") })
                    addProperty("username", "user")
                    addProperty("credential", "pass")
                })
            })
        }

        controller.onSignalEvent(activeSnapshot(), event("rtc.config", config))
        controller.onSignalEvent(activeSnapshot(), event("call.accept"))

        assertEquals(
            listOf(IceServerData(listOf("turn:relay.example.com:3478?transport=udp"), "user", "pass")),
            capturedServers,
        )
    }

    private fun activeSnapshot(): CallSnapshot = CallSnapshot(CallPhase.Active, callId, 1)

    private fun event(type: String, payload: JsonObject = JsonObject()): SignalEvent =
        SignalEvent("00000000-0000-0000-0000-000000000001", callId, type, 10L, payload)

    private class CapturingSignalClient : SignalClient {
        val sent = mutableListOf<SignalEvent>()
        override fun send(event: SignalEvent) {
            sent += event
        }
    }

    private class FakeMediaSession(
        private val offer: String = "offer",
        private val answer: String = "answer",
    ) : MediaSession {
        var acceptedOffer: String? = null
        var closed = false

        override suspend fun createOffer(): String = offer
        override suspend fun acceptOffer(sdp: String): String {
            acceptedOffer = sdp
            return answer
        }
        override suspend fun setAnswer(sdp: String) = Unit
        override suspend fun addIceCandidate(candidate: IceCandidateData) = Unit
        override suspend fun restartIce(): String = offer
        override fun setMuted(muted: Boolean) = Unit
        override suspend fun close() {
            closed = true
        }
    }

    private companion object {
        const val callId = "00000000-0000-0000-0000-000000000099"
    }
}
