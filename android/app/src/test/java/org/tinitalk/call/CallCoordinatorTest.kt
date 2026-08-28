package org.tinitalk.call

import com.google.gson.JsonObject
import org.tinitalk.data.signal.SignalEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallCoordinatorTest {
    @Test
    fun advertisesCrossedCallsAndAdoptsCanonicalCallId() {
        val signal = FakeSignalClient()
        val coordinator = CallCoordinator("alice", signal, FixedIds())
        coordinator.startCall("bob")

        assertTrue(signal.sent.single().payload["supports_cross_call"].asBoolean)

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

        assertEquals(CallSnapshot(CallPhase.Active, canonical, 2), coordinator.snapshot())
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

        assertEquals(CallSnapshot(), coordinator.snapshot())
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

    override fun send(event: SignalEvent, onSettled: (() -> Unit)?) {
        sent += event
        onSettled?.let { settlements[event.id] = it }
    }

    fun settle(eventId: String) = settlements.remove(eventId)?.invoke()
}

private class FixedIds : EventIds {
    override fun nextEventId(): String = "018f7d51-3f90-7e63-b657-4a83a6a90001"
    override fun nextCallId(): String = "018f7d51-40a1-7bb5-a2d0-7e47f9181000"
    override fun nowMillis(): Long = 1787666400000
}
