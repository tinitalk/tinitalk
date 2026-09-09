package org.tinitalk.call

import com.google.gson.JsonObject
import org.tinitalk.data.AccountId
import org.tinitalk.data.signal.SignalEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallCoordinatorTest {
    @Test
    fun advertisesCrossedCallsAndAdoptsCanonicalCallId() {
        val signal = FakeSignalClient()
        val coordinator = CallCoordinator(
            "alice",
            signal,
            FixedIds(),
        )
        coordinator.startCall("bob")

        assertTrue(signal.sent.single().payload["supports_cross_call"].asBoolean)
        assertTrue(signal.sent.single().payload["supports_video"].asBoolean)

        val canonical = "018f7d51-40a1-7bb5-a2d0-7e47f9182000"
        val payload = JsonObject().apply {
            addProperty("crossed", true)
            addProperty("offerer", false)
        }
        coordinator.onEvent(
            SequencedSignalEvent(
                SignalEvent(
                    "018f7d51-3f90-7e63-b657-4a83a6a92000",
                    canonical,
                    "call.accept",
                    1787666400000,
                    payload,
                ),
                2,
            ),
        )

        assertEquals(
            CallSnapshot(CallPhase.Active, canonical, 2, AccountId("single-account")),
            coordinator.snapshot(),
        )
    }

    @Test
    fun advertisesCallCapabilitiesWithStaleServerFeaturesInBothCallDirections() {
        val outgoingSignal = FakeSignalClient()
        CallCoordinator("alice", outgoingSignal, ids = FixedIds()).startCall("bob")
        val incomingSignal = FakeSignalClient()
        CallCoordinator("alice", incomingSignal, ids = FixedIds()).apply {
            onEvent(event("call.incoming", seq = 1))
            accept()
        }

        assertTrue(outgoingSignal.sent.single().payload["supports_video"].asBoolean)
        assertTrue(outgoingSignal.sent.single().payload["supports_exclusive_screen_sharing"].asBoolean)
        assertTrue(outgoingSignal.sent.single().payload["supports_call_sas"].asBoolean)
        assertTrue(incomingSignal.sent.last().payload["supports_video"].asBoolean)
        assertTrue(incomingSignal.sent.last().payload["supports_exclusive_screen_sharing"].asBoolean)
        assertTrue(incomingSignal.sent.last().payload["supports_call_sas"].asBoolean)
    }

    @Test
    fun startsAcceptsRejectsAndIgnoresOldEvents() {
        val signal = FakeSignalClient()
        val coordinator = CallCoordinator("alice", signal, ids = FixedIds())

        coordinator.startCall("bob")

        assertEquals("call.start", signal.sent.single().type)
        assertEquals(CallPhase.Connecting, coordinator.snapshot().phase)

        assertTrue(coordinator.onEvent(event("call.accept", seq = 2)))
        assertTrue(!coordinator.onEvent(event("call.ringing", seq = 1)))

        assertEquals(listOf("call.start"), signal.sent.map { it.type })
        assertEquals(CallPhase.Active, coordinator.snapshot().phase)

        val incoming = CallCoordinator("bob", signal, ids = FixedIds())
        incoming.onEvent(event("call.incoming", seq = 1))
        incoming.accept()
        assertEquals("call.accept", signal.sent.last().type)
        assertEquals(CallPhase.Active, incoming.snapshot().phase)
    }

    @Test
    fun restoresIncomingCallFromWakePayload() {
        val signal = FakeSignalClient()
        val coordinator = CallCoordinator("bob", signal, ids = FixedIds())

        coordinator.restoreIncoming("018f7d51-40a1-7bb5-a2d0-7e47f9181000", lastSeq = 1)
        coordinator.resume()
        coordinator.accept()

        assertEquals(CallPhase.Active, coordinator.snapshot().phase)
        assertEquals(listOf("call.ringing", "call.resume", "call.accept"), signal.sent.map { it.type })
        assertEquals(1L, signal.sent[1].payload["last_seq"].asLong)
    }

    @Test
    fun reportsWhenRingingAcknowledgementIsSettled() {
        val signal = FakeSignalClient()
        val coordinator = CallCoordinator("bob", signal, ids = FixedIds())
        var settled = false

        coordinator.restoreIncoming("018f7d51-40a1-7bb5-a2d0-7e47f9181000") { settled = true }

        assertFalse(settled)
        signal.settle(signal.sent.single().id)
        assertTrue(settled)
    }

    @Test
    fun restoresAlreadyAcknowledgedIncomingWithoutSendingRingingAgain() {
        val signal = FakeSignalClient()
        val coordinator = CallCoordinator("bob", signal, ids = FixedIds())

        coordinator.restoreIncoming(
            "018f7d51-40a1-7bb5-a2d0-7e47f9181000",
            lastSeq = 1,
            acknowledgeRinging = false,
        )

        assertEquals(CallPhase.Ringing, coordinator.snapshot().phase)
        assertTrue(signal.sent.isEmpty())
    }

    @Test
    fun outgoingCallBecomesRingingAfterTheOtherPhoneAcknowledgesIt() {
        val coordinator = CallCoordinator("alice", FakeSignalClient(), ids = FixedIds())
        coordinator.startCall("bob")

        coordinator.onEvent(event("call.ringing", seq = 1))

        assertEquals(CallPhase.Ringing, coordinator.snapshot().phase)
    }

    @Test
    fun reportsEstablishedMediaConnectionOnlyOnce() {
        val signal = FakeSignalClient()
        val coordinator = CallCoordinator("alice", signal, ids = FixedIds())
        coordinator.startCall("bob")
        coordinator.onEvent(event("call.accept", seq = 1))

        coordinator.mediaConnected()
        coordinator.mediaConnected()

        assertEquals(listOf("call.start", "call.connected"), signal.sent.map { it.type })
        signal.sent.last().encode()
    }

    @Test
    fun resumesAfterReconnectFromLastSequence() {
        val signal = FakeSignalClient()
        val coordinator = CallCoordinator("alice", signal, ids = FixedIds())
        coordinator.onEvent(event("call.incoming", seq = 7))

        coordinator.resume()

        val sent = signal.sent.single()
        assertEquals("call.resume", sent.type)
        assertTrue(sent.payload["last_seq"].asLong == 7L)
    }

    @Test
    fun endsActiveCallAndStartsAnotherCallAfterCleanup() {
        val signal = FakeSignalClient()
        val coordinator = CallCoordinator("alice", signal, ids = FixedIds())
        coordinator.startCall("bob")
        coordinator.onEvent(event("call.accept", seq = 1))

        coordinator.hangUp()
        coordinator.finish()
        coordinator.startCall("carol")

        assertEquals(listOf("call.start", "call.end", "call.start"), signal.sent.map { it.type })
        assertEquals(CallPhase.Connecting, coordinator.snapshot().phase)
    }

    @Test
    fun terminalEventReportsSettlementOnlyAfterSignalClientConfirmsIt() {
        val signal = FakeSignalClient()
        val coordinator = CallCoordinator("alice", signal, ids = FixedIds())
        coordinator.startCall("bob")
        coordinator.onEvent(event("call.accept", seq = 1))
        var settled = false

        coordinator.hangUp { settled = true }

        assertEquals(CallPhase.Ended, coordinator.snapshot().phase)
        assertFalse(settled)
        signal.settle(signal.sent.last().id)
        assertTrue(settled)
    }

    @Test
    fun rejectWithReplySendsOnlyTheStableCodeAndReportsAcknowledgement() {
        val signal = FakeSignalClient()
        val coordinator = CallCoordinator("bob", signal, ids = FixedIds())
        coordinator.onEvent(event("call.incoming", seq = 1))
        var result: SignalSendResult? = null

        val eventId = "018f7d51-3f90-7e63-b657-4a83a6a90003"
        coordinator.reject(CallReplyCode.CannotTalk, eventId) { result = it }

        val sent = signal.sent.last()
        assertEquals(eventId, sent.id)
        assertEquals("call.reject", sent.type)
        assertEquals(setOf("reply_code"), sent.payload.keySet())
        assertEquals("cannot_talk", sent.payload["reply_code"].asString)
        assertEquals(null, result)
        signal.acknowledge(sent.id)
        assertEquals(SignalSendResult.Acknowledged, result)
    }

    @Test
    fun ordinaryRejectCanReusePersistedEventId() {
        val signal = FakeSignalClient()
        val coordinator = CallCoordinator("bob", signal, ids = FixedIds())
        coordinator.onEvent(event("call.incoming", seq = 1))
        val eventId = "018f7d51-3f90-7e63-b657-4a83a6a90004"

        coordinator.reject(eventId) {}

        assertEquals(eventId, signal.sent.single().id)
        assertEquals(0, signal.sent.single().payload.size())
    }

    @Test
    fun rejectWithReplyReportsCorrelatedServerRejectionAsFailure() {
        val signal = FakeSignalClient()
        val coordinator = CallCoordinator("bob", signal, ids = FixedIds())
        coordinator.onEvent(event("call.incoming", seq = 1))
        var result: SignalSendResult? = null

        coordinator.reject(CallReplyCode.WillCallBack) { result = it }
        signal.reject(signal.sent.last().id)

        assertEquals(SignalSendResult.Rejected, result)
    }

    @Test
    fun replayedTerminalEventAfterLocalRejectIsIdempotent() {
        val signal = FakeSignalClient()
        val coordinator = CallCoordinator("bob", signal, ids = FixedIds())
        val incoming = event("call.incoming", seq = 1)
        coordinator.onEvent(incoming)
        coordinator.reject(CallReplyCode.CallMeLater) {}

        assertTrue(coordinator.onEvent(event("call.reject", seq = 2)))

        assertEquals(CallPhase.Ended, coordinator.snapshot().phase)
        assertEquals(2L, coordinator.snapshot().lastSeq)
        assertEquals(listOf("call.reject"), signal.sent.map { it.type })
    }

    @Test
    fun foreignTerminalEventCannotEndOrAdvanceCurrentCall() {
        val coordinator = CallCoordinator("alice", FakeSignalClient(), ids = FixedIds())
        coordinator.startCall("bob", callId = "current-call")
        val foreign = SequencedSignalEvent(
            SignalEvent("foreign-event", "foreign-call", "call.reject", 1787666400000, JsonObject()),
            7,
        )

        assertFalse(coordinator.onEvent(foreign))

        assertEquals(CallSnapshot(CallPhase.Connecting, "current-call", 0, AccountId("single-account")), coordinator.snapshot())
    }

    @Test
    fun endsCallAfterSignalingProtocolError() {
        val coordinator = CallCoordinator("alice", FakeSignalClient(), ids = FixedIds())
        coordinator.startCall("bob")

        coordinator.fail()

        assertEquals(CallPhase.Ended, coordinator.snapshot().phase)
    }

    @Test
    fun finishResetsCoordinatorOnlyAfterEndedState() {
        val coordinator = CallCoordinator("alice", FakeSignalClient(), FixedIds())
        coordinator.startCall("bob")
        coordinator.fail()

        coordinator.finish()

        assertEquals(CallSnapshot(accountId = AccountId("single-account")), coordinator.snapshot())
    }

    private fun event(type: String, seq: Long): SequencedSignalEvent =
        SequencedSignalEvent(
            SignalEvent(
                id = "018f7d51-3f90-7e63-b657-4a83a6a91000",
                callId = "018f7d51-40a1-7bb5-a2d0-7e47f9181000",
                type = type,
                sentAt = 1787666400000,
                payload = JsonObject(),
            ),
            seq,
        )
}

private class FakeSignalClient : SignalClient {
    val sent = mutableListOf<SignalEvent>()
    private val settlements = mutableMapOf<String, () -> Unit>()
    private val results = mutableMapOf<String, (SignalSendResult) -> Unit>()

    override fun send(event: SignalEvent, onSettled: (() -> Unit)?) {
        sent += event
        onSettled?.let { settlements[event.id] = it }
    }

    override fun sendTracked(event: SignalEvent, onResult: (SignalSendResult) -> Unit) {
        sent += event
        results[event.id] = onResult
    }

    fun settle(eventId: String) = settlements.remove(eventId)?.invoke()
    fun acknowledge(eventId: String) = results.remove(eventId)?.invoke(SignalSendResult.Acknowledged)
    fun reject(eventId: String) = results.remove(eventId)?.invoke(SignalSendResult.Rejected)
}

private class FixedIds : EventIds {
    override fun nextEventId(): String = "018f7d51-3f90-7e63-b657-4a83a6a90001"
    override fun nextCallId(): String = "018f7d51-40a1-7bb5-a2d0-7e47f9181000"
    override fun nowMillis(): Long = 1787666400000
}
