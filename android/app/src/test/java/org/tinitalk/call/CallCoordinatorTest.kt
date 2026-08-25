package org.tinitalk.call

import com.google.gson.JsonObject
import org.tinitalk.data.signal.SignalEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CallCoordinatorTest {
    @Test
    fun startsAcceptsRejectsAndIgnoresOldEvents() {
        val signal = FakeSignalClient()
        val coordinator = CallCoordinator("alice", signal, ids = FixedIds())

        coordinator.startCall("bob")

        assertEquals("call.start", signal.sent.single().type)
        assertEquals(CallPhase.Connecting, coordinator.snapshot().phase)

        coordinator.onEvent(event("call.accept", seq = 2))
        coordinator.onEvent(event("call.ringing", seq = 1))

        assertEquals(listOf("call.start"), signal.sent.map { it.type })
        assertEquals(CallPhase.Active, coordinator.snapshot().phase)

        val incoming = CallCoordinator("bob", signal, ids = FixedIds())
        incoming.onEvent(event("call.incoming", seq = 1))
        incoming.accept()
        assertEquals("call.accept", signal.sent.last().type)
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
    override fun send(event: SignalEvent) {
        sent += event
    }
}

private class FixedIds : EventIds {
    override fun nextEventId(): String = "018f7d51-3f90-7e63-b657-4a83a6a90001"
    override fun nextCallId(): String = "018f7d51-40a1-7bb5-a2d0-7e47f9181000"
    override fun nowMillis(): Long = 1787666400000
}
