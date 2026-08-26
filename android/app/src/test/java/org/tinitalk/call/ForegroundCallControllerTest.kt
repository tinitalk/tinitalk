package org.tinitalk.call

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import org.tinitalk.data.signal.SignalEvent
import org.tinitalk.media.IceCandidateData
import org.tinitalk.media.IceServerData
import org.tinitalk.media.MediaSession
import org.tinitalk.media.CancellableTask
import org.tinitalk.media.TaskScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.lang.reflect.Modifier

class ForegroundCallControllerTest {
    private val ids = object : EventIds {
        private var next = 1
        override fun nextEventId(): String = "00000000-0000-0000-0000-${(next++).toString().padStart(12, '0')}"
        override fun nextCallId(): String = "00000000-0000-0000-0000-000000000099"
        override fun nowMillis(): Long = 10L
    }

    @Test
    fun activityAndMuteSettersUseControllerMonitor() {
        val controller = ForegroundCallController::class.java

        assertTrue(Modifier.isSynchronized(controller.getDeclaredMethod("setActive", Boolean::class.javaPrimitiveType).modifiers))
        assertTrue(Modifier.isSynchronized(controller.getDeclaredMethod("setMuted", Boolean::class.javaPrimitiveType).modifiers))
    }

    @Test
    fun callerWaitsForIceConfigBeforeCreatingOffer() {
        val signal = CapturingSignalClient()
        val media = FakeMediaSession(offer = "local-offer")
        val controller = ForegroundCallController(signal, { _, _, _, _ -> media }, ids)

        controller.onSignalEvent(activeSnapshot(), event("call.accept"))

        assertTrue(signal.sent.isEmpty())
        controller.onSignalEvent(activeSnapshot(), event("rtc.config", emptyIceConfig()))

        assertEquals("rtc.offer", signal.sent.single().type)
        assertEquals("local-offer", signal.sent.single().payload["sdp"].asString)
    }

    @Test
    fun calleeWaitsForIceConfigBeforeAnsweringOffer() {
        val signal = CapturingSignalClient()
        val media = FakeMediaSession(answer = "local-answer")
        val controller = ForegroundCallController(signal, { _, _, _, _ -> media }, ids)
        val payload = JsonObject().apply { addProperty("sdp", "remote-offer") }

        controller.onSignalEvent(activeSnapshot(), event("rtc.offer", payload))

        assertTrue(signal.sent.isEmpty())
        controller.onSignalEvent(activeSnapshot(), event("rtc.config", emptyIceConfig()))

        assertEquals("remote-offer", media.acceptedOffer)
        assertEquals("rtc.answer", signal.sent.single().type)
        assertEquals("local-answer", signal.sent.single().payload["sdp"].asString)
    }

    @Test
    fun localIceCandidateIsSentThroughSignalClient() {
        val signal = CapturingSignalClient()
        lateinit var localIce: (IceCandidateData) -> Unit
        ForegroundCallController(signal, { _, _, callback, _ ->
            localIce = callback
            FakeMediaSession()
        }, ids).also {
            it.onSignalEvent(activeSnapshot(), event("rtc.config", emptyIceConfig()))
            it.onSignalEvent(activeSnapshot(), event("call.accept"))
        }

        localIce(IceCandidateData("audio", 0, "candidate:1"))

        assertEquals("rtc.ice", signal.sent.last().type)
        assertEquals("candidate:1", signal.sent.last().payload["candidate"].asString)
    }

    @Test
    fun terminalEventClosesMediaSession() {
        val signal = CapturingSignalClient()
        val media = FakeMediaSession()
        val controller = ForegroundCallController(signal, { _, _, _, _ -> media }, ids)
        controller.onSignalEvent(activeSnapshot(), event("rtc.config", emptyIceConfig()))
        controller.onSignalEvent(activeSnapshot(), event("call.accept"))

        controller.onSignalEvent(CallSnapshot(CallPhase.Ended, callId, 2), event("call.end"))

        assertTrue(media.closed)
    }

    @Test
    fun activityUpdatesCurrentMediaSession() {
        val media = FakeMediaSession()
        val controller = ForegroundCallController(CapturingSignalClient(), { _, _, _, _ -> media }, ids)
        controller.onSignalEvent(activeSnapshot(), event("rtc.config", emptyIceConfig()))
        controller.onSignalEvent(activeSnapshot(), event("call.accept"))

        controller.setActive(true)
        controller.setActive(false)

        assertEquals(listOf(false, true, false), media.activity)
    }

    @Test
    fun appliesActivitySetBeforeMediaSessionCreation() {
        val media = FakeMediaSession()
        val controller = ForegroundCallController(CapturingSignalClient(), { _, _, _, _ -> media }, ids)

        controller.setActive(true)
        controller.onSignalEvent(activeSnapshot(), event("rtc.config", emptyIceConfig()))
        controller.onSignalEvent(activeSnapshot(), event("call.accept"))

        assertEquals(listOf(true), media.activity)
    }

    @Test
    fun appliesMuteSetBeforeMediaSessionCreation() {
        val media = FakeMediaSession()
        val controller = ForegroundCallController(CapturingSignalClient(), { _, _, _, _ -> media }, ids)

        controller.setMuted(true)
        controller.onSignalEvent(activeSnapshot(), event("rtc.config", emptyIceConfig()))
        controller.onSignalEvent(activeSnapshot(), event("call.accept"))

        assertEquals(listOf(true), media.muted)
    }

    @Test
    fun usesIceServersFromRtcConfigWhenCreatingMediaSession() {
        var capturedServers = emptyList<IceServerData>()
        val controller = ForegroundCallController(CapturingSignalClient(), { _, servers, _, _ ->
            capturedServers = servers
            FakeMediaSession()
        }, ids)
        val config = JsonObject().apply {
            add("ice_servers", JsonArray().apply {
                add(JsonObject().apply {
                    add("urls", JsonArray().apply { add("turn:relay.example.com:3478?transport=udp") })
                    addProperty("username", "user")
                    addProperty("credential", "pass")
                    addProperty("expires_at", "2026-08-26T10:10:00Z")
                })
            })
        }

        controller.onSignalEvent(activeSnapshot(), event("rtc.config", config))
        controller.onSignalEvent(activeSnapshot(), event("call.accept"))

        assertEquals(
            listOf(IceServerData(
                listOf("turn:relay.example.com:3478?transport=udp"),
                "user",
                "pass",
                Instant.parse("2026-08-26T10:10:00Z"),
            )),
            capturedServers,
        )
    }

    @Test
    fun ignoresMissingAndInvalidIceServerExpiries() {
        var capturedServers = emptyList<IceServerData>()
        val controller = ForegroundCallController(CapturingSignalClient(), { _, servers, _, _ ->
            capturedServers = servers
            FakeMediaSession()
        }, ids)
        val config = JsonObject().apply {
            add("ice_servers", JsonArray().apply {
                add(iceServer())
                add(iceServer(JsonNull.INSTANCE))
                add(iceServer(JsonPrimitive("not-a-timestamp")))
                add(iceServer(JsonObject()))
                add(iceServer(JsonArray()))
            })
        }

        controller.onSignalEvent(activeSnapshot(), event("rtc.config", config))
        controller.onSignalEvent(activeSnapshot(), event("call.accept"))

        assertEquals(listOf(null, null, null, null, null), capturedServers.map { it.expiresAt })
    }

    @Test
    fun restartWaitsForFreshCredentialsBeforeCreatingOffer() {
        val signal = CapturingSignalClient()
        val media = FakeMediaSession(offer = "restart-offer")
        lateinit var restart: () -> Unit
        val controller = ForegroundCallController(signal, { _, _, _, callback ->
            restart = callback
            media
        }, ids)
        controller.onSignalEvent(activeSnapshot(), event("rtc.config", emptyIceConfig()))
        controller.onSignalEvent(activeSnapshot(), event("call.accept"))
        signal.sent.clear()
        media.updatedServers.clear()

        restart()

        assertEquals(listOf("rtc.restart"), signal.sent.map { it.type })

        val refreshed = iceConfig("fresh-user", "fresh-pass", Instant.ofEpochMilli(120_000L), signal.sent.single().id)
        controller.onSignalEvent(activeSnapshot(), event("rtc.config", refreshed))

        assertEquals(listOf(IceServerData(listOf("turn:relay.example.com:3478?transport=udp"), "fresh-user", "fresh-pass", Instant.ofEpochMilli(120_000L))), media.updatedServers)
        assertEquals("rtc.offer", signal.sent.last().type)
        assertEquals("restart-offer", signal.sent.last().payload["sdp"].asString)
    }

    @Test
    fun staleOrMismatchedConfigDoesNotCompletePendingRestart() {
        val signal = CapturingSignalClient()
        val media = FakeMediaSession(offer = "restart-offer")
        val scheduler = FakeTaskScheduler()
        lateinit var restart: () -> Unit
        val controller = ForegroundCallController(signal, { _, _, _, callback ->
            restart = callback
            media
        }, ids, scheduler)
        controller.onSignalEvent(activeSnapshot(), event("rtc.config", emptyIceConfig()))
        controller.onSignalEvent(activeSnapshot(), event("call.accept"))
        signal.sent.clear()
        media.updatedServers.clear()

        restart()
        val restartID = signal.sent.single().id
        controller.onSignalEvent(activeSnapshot(), event("rtc.config", iceConfig("stale-user", "stale-pass", Instant.ofEpochMilli(120_000L))))
        controller.onSignalEvent(activeSnapshot(), event("rtc.config", iceConfig("wrong-user", "wrong-pass", Instant.ofEpochMilli(120_000L), "other-restart")))

        assertEquals(listOf("rtc.restart"), signal.sent.map { it.type })
        controller.onSignalEvent(activeSnapshot(), event("rtc.config", iceConfig("fresh-user", "fresh-pass", Instant.ofEpochMilli(120_000L), restartID)))

        assertEquals(listOf("rtc.restart", "rtc.offer"), signal.sent.map { it.type })
        assertEquals(listOf(IceServerData(listOf("turn:relay.example.com:3478?transport=udp"), "fresh-user", "fresh-pass", Instant.ofEpochMilli(120_000L))), media.updatedServers)
    }

    @Test
    fun replayedConfigDoesNotCreateAnotherOfferWithoutPendingRestart() {
        val signal = CapturingSignalClient()
        val controller = ForegroundCallController(signal, { _, _, _, _ -> FakeMediaSession() }, ids)

        controller.onSignalEvent(activeSnapshot(), event("call.accept"))
        controller.onSignalEvent(activeSnapshot(), event("rtc.config", emptyIceConfig()))
        signal.sent.clear()

        controller.onSignalEvent(activeSnapshot(), event("rtc.config", emptyIceConfig()))

        assertTrue(signal.sent.isEmpty())
    }

    @Test
    fun onlyOffererSchedulesCredentialRefreshBeforeExpiry() {
        val callerSignal = CapturingSignalClient()
        val calleeSignal = CapturingSignalClient()
        val callerScheduler = FakeTaskScheduler()
        val calleeScheduler = FakeTaskScheduler()
        val caller = ForegroundCallController(callerSignal, { _, _, _, _ -> FakeMediaSession() }, ids, callerScheduler)
        val callee = ForegroundCallController(calleeSignal, { _, _, _, _ -> FakeMediaSession() }, ids, calleeScheduler)
        val expiring = iceConfig("user", "pass", Instant.ofEpochMilli(65_010L))

        caller.onSignalEvent(activeSnapshot(), event("call.accept"))
        caller.onSignalEvent(activeSnapshot(), event("rtc.config", expiring))
        caller.onSignalEvent(activeSnapshot(), event("rtc.config", expiring))
        callee.onSignalEvent(activeSnapshot(), event("rtc.config", expiring))
        callee.onSignalEvent(activeSnapshot(), event("rtc.offer", JsonObject().apply { addProperty("sdp", "remote-offer") }))

        assertEquals(1, callerScheduler.pendingCount)
        assertEquals(0, calleeScheduler.pendingCount)
        assertEquals(listOf(5_000L, 5_000L), callerScheduler.delays)
    }

    @Test
    fun schedulesEarliestCredentialExpiryAtLeadTimeClampedAtZero() {
        val signal = CapturingSignalClient()
        val scheduler = FakeTaskScheduler()
        val controller = ForegroundCallController(signal, { _, _, _, _ -> FakeMediaSession() }, ids, scheduler)
        val config = iceConfig("later-user", "later-pass", Instant.ofEpochMilli(120_010L))
        config.getAsJsonArray("ice_servers").add(iceServer(JsonPrimitive(Instant.ofEpochMilli(65_010L).toString())))

        controller.onSignalEvent(activeSnapshot(), event("call.accept"))
        controller.onSignalEvent(activeSnapshot(), event("rtc.config", config))
        controller.onSignalEvent(activeSnapshot(), event("rtc.config", iceConfig("expired-user", "expired-pass", Instant.ofEpochMilli(10L))))

        assertEquals(listOf(5_000L, 0L), scheduler.delays)
    }

    @Test
    fun closeCancelsCredentialRefreshTask() {
        val signal = CapturingSignalClient()
        val scheduler = FakeTaskScheduler()
        val controller = ForegroundCallController(signal, { _, _, _, _ -> FakeMediaSession() }, ids, scheduler)

        controller.onSignalEvent(activeSnapshot(), event("call.accept"))
        controller.onSignalEvent(activeSnapshot(), event("rtc.config", iceConfig("user", "pass", Instant.ofEpochMilli(65_010L))))
        signal.sent.clear()
        controller.close()
        scheduler.runPending()

        assertTrue(signal.sent.none { it.type == "rtc.restart" })
        assertTrue(scheduler.closed)
    }

    @Test
    fun networkAndExpiryRefreshRequestsAreCoalesced() {
        val signal = CapturingSignalClient()
        val scheduler = FakeTaskScheduler()
        lateinit var restart: () -> Unit
        val controller = ForegroundCallController(signal, { _, _, _, callback ->
            restart = callback
            FakeMediaSession()
        }, ids, scheduler)

        controller.onSignalEvent(activeSnapshot(), event("call.accept"))
        controller.onSignalEvent(activeSnapshot(), event("rtc.config", iceConfig("user", "pass", Instant.ofEpochMilli(65_010L))))
        signal.sent.clear()

        restart()
        scheduler.runPending()

        assertEquals(listOf("rtc.restart"), signal.sent.map { it.type })
    }

    @Test
    fun calleeUpdatesRefreshedCredentialsWithoutCreatingOffer() {
        val signal = CapturingSignalClient()
        val media = FakeMediaSession()
        val controller = ForegroundCallController(signal, { _, _, _, _ -> media }, ids)

        controller.onSignalEvent(activeSnapshot(), event("rtc.config", emptyIceConfig()))
        controller.onSignalEvent(activeSnapshot(), event("rtc.offer", JsonObject().apply { addProperty("sdp", "remote-offer") }))
        signal.sent.clear()
        media.updatedServers.clear()

        controller.onSignalEvent(activeSnapshot(), event("rtc.config", iceConfig("fresh-user", "fresh-pass", Instant.ofEpochMilli(120_000L))))

        assertEquals(listOf(IceServerData(listOf("turn:relay.example.com:3478?transport=udp"), "fresh-user", "fresh-pass", Instant.ofEpochMilli(120_000L))), media.updatedServers)
        assertTrue(signal.sent.isEmpty())
    }

    @Test
    fun onlyInitialOffererSendsRestartOffersAcrossOutages() {
        val callerSignal = CapturingSignalClient()
        val calleeSignal = CapturingSignalClient()
        lateinit var callerRestart: () -> Unit
        lateinit var calleeRestart: () -> Unit
        val caller = ForegroundCallController(callerSignal, { _, _, _, callback ->
            callerRestart = callback
            FakeMediaSession(offer = "caller-restart")
        }, ids)
        val callee = ForegroundCallController(calleeSignal, { _, _, _, callback ->
            calleeRestart = callback
            FakeMediaSession(offer = "callee-restart")
        }, ids)

        caller.onSignalEvent(activeSnapshot(), event("call.accept"))
        caller.onSignalEvent(activeSnapshot(), event("rtc.config", emptyIceConfig()))
        callee.onSignalEvent(activeSnapshot(), event("rtc.config", emptyIceConfig()))
        callee.onSignalEvent(activeSnapshot(), event("rtc.offer", JsonObject().apply { addProperty("sdp", "remote-offer") }))
        callerSignal.sent.clear()
        calleeSignal.sent.clear()

        callerRestart()
        calleeRestart()
        callerRestart()

        assertEquals(listOf("rtc.restart"), callerSignal.sent.map { it.type })
        assertTrue(calleeSignal.sent.isEmpty())
    }

    @Test
    fun keepsRemoteIceCandidateThatArrivesBeforeOffer() {
        val media = FakeMediaSession()
        val controller = ForegroundCallController(CapturingSignalClient(), { _, _, _, _ -> media }, ids)
        val candidate = JsonObject().apply {
            addProperty("sdp_mid", "audio")
            addProperty("sdp_mline_index", 0)
            addProperty("candidate", "candidate:early")
        }
        val offer = JsonObject().apply { addProperty("sdp", "remote-offer") }

        controller.onSignalEvent(activeSnapshot(), event("rtc.config", emptyIceConfig()))
        controller.onSignalEvent(activeSnapshot(), event("rtc.ice", candidate))
        controller.onSignalEvent(activeSnapshot(), event("rtc.offer", offer))

        assertEquals(listOf("candidate:early"), media.remoteCandidates.map { it.candidate })
    }

    private fun activeSnapshot(): CallSnapshot = CallSnapshot(CallPhase.Active, callId, 1)

    private fun event(type: String, payload: JsonObject = JsonObject()): SignalEvent =
        SignalEvent("00000000-0000-0000-0000-000000000001", callId, type, 10L, payload)

    private fun emptyIceConfig() = JsonObject().apply { add("ice_servers", JsonArray()) }

    private fun iceConfig(username: String, password: String, expiresAt: Instant, restartID: String? = null): JsonObject = JsonObject().apply {
        add("ice_servers", JsonArray().apply {
            add(JsonObject().apply {
                add("urls", JsonArray().apply { add("turn:relay.example.com:3478?transport=udp") })
                addProperty("username", username)
                addProperty("credential", password)
                addProperty("expires_at", expiresAt.toString())
            })
        })
        restartID?.let { addProperty("restart_id", it) }
    }

    private fun iceServer(expiry: JsonElement? = null): JsonObject = JsonObject().apply {
        add("urls", JsonArray().apply { add("turn:relay.example.com:3478?transport=udp") })
        if (expiry != null) add("expires_at", expiry)
    }

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
        val remoteCandidates = mutableListOf<IceCandidateData>()
        val updatedServers = mutableListOf<IceServerData>()
        val activity = mutableListOf<Boolean>()
        val muted = mutableListOf<Boolean>()

        override suspend fun createOffer(): String = offer
        override suspend fun acceptOffer(sdp: String): String {
            acceptedOffer = sdp
            return answer
        }
        override suspend fun setAnswer(sdp: String) = Unit
        override suspend fun addIceCandidate(candidate: IceCandidateData) {
            remoteCandidates += candidate
        }
        override suspend fun restartIce(): String = offer
        override suspend fun updateIceServers(servers: List<IceServerData>) {
            updatedServers.clear()
            updatedServers += servers
        }
        override fun setMuted(muted: Boolean) {
            this.muted += muted
        }
        override fun setActive(active: Boolean) {
            activity += active
        }
        override suspend fun close() {
            closed = true
        }
    }

    private class FakeTaskScheduler : TaskScheduler {
        private val tasks = mutableListOf<FakeTask>()
        val delays = mutableListOf<Long>()
        var closed = false
        val pendingCount: Int get() = tasks.count { !it.cancelled }

        override fun schedule(delayMillis: Long, action: () -> Unit): CancellableTask {
            delays += delayMillis
            val task = FakeTask(action)
            tasks += task
            return CancellableTask { task.cancelled = true }
        }

        override fun close() {
            closed = true
            tasks.forEach { it.cancelled = true }
        }

        fun runPending() {
            tasks.filterNot { it.cancelled }.forEach {
                it.cancelled = true
                it.action()
            }
        }

        private class FakeTask(val action: () -> Unit) {
            var cancelled = false
        }
    }

    private companion object {
        const val callId = "00000000-0000-0000-0000-000000000099"
    }
}
