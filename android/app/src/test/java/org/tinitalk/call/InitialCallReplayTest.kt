package org.tinitalk.call

import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test
import org.tinitalk.data.signal.SignalEvent
import java.util.UUID

class InitialCallReplayTest {
    private val callId = UUID.randomUUID().toString()
    private fun event(type: String, seq: Long, call: String = callId) = SequencedSignalEvent(
        SignalEvent(UUID.randomUUID().toString(), call, type, 0, JsonObject()), seq,
    )

    @Test fun liveIceDoesNotDiscardReplayedConfigurationOfferOrSecurityMessages() {
        val coordinator = CallCoordinator("callee", object : SignalClient {
            override fun send(event: SignalEvent, onSettled: (() -> Unit)?) = Unit
        })
        coordinator.restoreAcceptedIncoming(callId, 1)
        val replay = InitialCallReplay(callId, 1)
        val ice = event("rtc.ice", 8)
        replay.add(ice)
        replay.add(event("rtc.config", 4))
        replay.add(event("rtc.sas.commit", 5))
        replay.add(event("rtc.offer", 6))
        replay.add(ice)
        val events = replay.drain()
        assertEquals(listOf(4L, 5L, 6L, 8L), events.map { it.seq })
        events.forEach { assertTrue("Dropped ${it.event.type}", coordinator.onEvent(it)) }
        assertFalse(coordinator.onEvent(ice))
        assertEquals(CallPhase.Active, coordinator.snapshot().phase)
        assertTrue(replay.drain().isEmpty())
    }

    @Test fun ignoresOtherCallsAndEventsBeforeTheInvitation() {
        val replay = InitialCallReplay(callId, 3)
        replay.add(event("call.end", 100, UUID.randomUUID().toString()))
        replay.add(event("call.incoming", 3))
        replay.add(event("rtc.config", 4))
        assertEquals(listOf(4L), replay.drain().map { it.seq })
    }

    @Test fun reconnectDoesNotStartASecondReplayWhileTrackedRequestIsPending() {
        val replay = InitialCallReplay(callId, 1)
        assertTrue(replay.request())
        assertFalse(replay.request())
    }

    @Test fun terminalEventIsPreservedAfterReplayedMedia() {
        val replay = InitialCallReplay(callId, 1)
        replay.add(event("call.end", 9))
        replay.add(event("rtc.config", 4))
        assertEquals(listOf("rtc.config", "call.end"), replay.drain().map { it.event.type })
    }

    @Test fun duplicateEventsDoNotConsumeBufferCapacity() {
        val replay = InitialCallReplay(callId, 0)
        for (seq in 1..SignalEvent.EVENT_BUFFER_LIMIT) replay.add(event("rtc.ice", seq.toLong()))
        replay.add(event("rtc.ice", 1))
        assertThrows(IllegalStateException::class.java) {
            replay.add(event("rtc.ice", SignalEvent.EVENT_BUFFER_LIMIT.toLong() + 1))
        }
    }
}
