package org.tinitalk.call

import com.google.gson.JsonObject
import org.tinitalk.data.signal.SignalEvent
import org.tinitalk.media.CallStats
import org.tinitalk.media.CancellableTask
import org.tinitalk.media.IceCandidateData
import org.tinitalk.media.IceServerData
import org.tinitalk.media.MediaSession
import org.tinitalk.media.TaskScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class CallNegotiationTest {
    @Test
    fun queuedOfferBindsSecurityBeforeAnswerIsSent() {
        val order = mutableListOf<String>()
        val media = FakeMedia()
        val negotiation = negotiation(media, order)

        negotiation.onOffer(event("rtc.offer", "sdp", "remote-offer"))
        assertEquals(emptyList<String>(), order)
        negotiation.configure(event("rtc.config"), false) { order += "security configured" }

        assertEquals(
            listOf("security configured", "remote:remote-offer", "local:local-answer", "send:rtc.answer"),
            order,
        )
        assertEquals("remote-offer", media.offer)
    }

    @Test
    fun detachingLeavesDisposalToOwnerAndIgnoresLateLocalIce() {
        val order = mutableListOf<String>()
        val media = FakeMedia()
        lateinit var localIce: (IceCandidateData) -> Unit
        lateinit var restart: () -> Unit
        val negotiation = negotiation(media, order) { added, retry -> localIce = added; restart = retry }
        negotiation.onAccepted(CallId, true)
        negotiation.onIce(event("rtc.ice", "candidate", "early").apply {
            payload.addProperty("sdp_mid", "audio")
            payload.addProperty("sdp_mline_index", 0)
        })
        negotiation.configure(event("rtc.config"), false) {}
        assertEquals(listOf(IceCandidateData("audio", 0, "early")), media.candidates)
        order.clear()

        negotiation.cancelPendingTasks()
        assertSame(media, negotiation.detachSession())
        assertFalse(media.closed)
        localIce(IceCandidateData("audio", 0, "late"))
        restart()

        assertEquals(emptyList<String>(), order)
    }

    private fun negotiation(
        media: FakeMedia,
        order: MutableList<String>,
        callbacks: ((IceCandidateData) -> Unit, () -> Unit) -> Unit = { _, _ -> },
    ) = CallNegotiation(
        signal = object : SignalClient {
            override fun send(event: SignalEvent, onSettled: (() -> Unit)?) { order += "send:${event.type}" }
        },
        mediaFactory = { _, _, _, added, _, restart -> callbacks(added, restart); media },
        ids = UuidEventIds(),
        scheduler = object : TaskScheduler {
            override fun schedule(delayMillis: Long, action: () -> Unit) = CancellableTask {}
            override fun close() = Unit
        },
        lock = Any(),
        onLocalSdp = { _, sdp -> order += "local:$sdp" },
        onRemoteSdp = { _, sdp -> order += "remote:$sdp" },
    )

    private fun event(type: String, key: String? = null, value: String = "") = SignalEvent(
        "00000000-0000-0000-0000-000000000002", CallId, type, 1L,
        JsonObject().apply { if (key != null) addProperty(key, value) },
    )

    private class FakeMedia : MediaSession {
        var offer: String? = null
        var closed = false
        val candidates = mutableListOf<IceCandidateData>()
        override suspend fun createOffer() = "local-offer"
        override suspend fun acceptOffer(sdp: String): String { offer = sdp; return "local-answer" }
        override suspend fun setAnswer(sdp: String) = Unit
        override suspend fun addIceCandidate(candidate: IceCandidateData) { candidates += candidate }
        override suspend fun removeIceCandidates(candidates: List<IceCandidateData>) = Unit
        override suspend fun restartIce() = "restart-offer"
        override suspend fun updateIceServers(servers: List<IceServerData>) = Unit
        override fun beginRemoteDescription() = Unit
        override fun onNetworkChanged() = Unit
        override fun setMuted(muted: Boolean) = Unit
        override fun setActive(active: Boolean) = Unit
        override fun getStats(onResult: (CallStats) -> Unit) = Unit
        override suspend fun close() { closed = true }
    }

    private companion object {
        const val CallId = "00000000-0000-0000-0000-000000000001"
    }
}
