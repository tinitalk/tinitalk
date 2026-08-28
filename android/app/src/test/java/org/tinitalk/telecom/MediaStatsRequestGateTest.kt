package org.tinitalk.telecom

import org.tinitalk.media.MediaConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaStatsRequestGateTest {
    @Test
    fun connectedIsIdempotentAndOnlyOneStatsRequestCanBeInFlight() {
        val gate = MediaStatsRequestGate()
        val session = gate.openSession(CurrentCall)
        val connecting = gate.onConnection(session, MediaConnectionState.Connecting)
        assertFalse(requireNotNull(connecting).transportReady)
        assertNull(gate.begin(session))

        val connected = requireNotNull(gate.onConnection(session, MediaConnectionState.Connected))
        val token = requireNotNull(gate.begin(session))
        val duplicate = requireNotNull(gate.onConnection(session, MediaConnectionState.Connected))

        assertEquals(connected.epoch, duplicate.epoch)
        assertFalse(duplicate.becameReady)
        assertNull(gate.begin(session))
        assertTrue(gate.complete(token))
    }

    @Test
    fun reconnectInvalidatesOldCallbackWithoutClearingANewerRequest() {
        val gate = MediaStatsRequestGate()
        val session = gate.openSession(CurrentCall)
        gate.onConnection(session, MediaConnectionState.Connected)
        val stale = requireNotNull(gate.begin(session))

        val disconnected = requireNotNull(gate.onConnection(session, MediaConnectionState.Disconnected))
        assertFalse(disconnected.transportReady)
        gate.onConnection(session, MediaConnectionState.Connected)
        val current = requireNotNull(gate.begin(session))

        assertFalse(gate.complete(stale))
        assertNull(gate.begin(session))
        assertTrue(gate.complete(current))
    }

    @Test
    fun replacementAndResetRejectStaleSessionEventsAndTokens() {
        val gate = MediaStatsRequestGate()
        val oldSession = gate.openSession(CurrentCall)
        gate.onConnection(oldSession, MediaConnectionState.Connected)
        val oldToken = requireNotNull(gate.begin(oldSession))
        val replacement = gate.openSession(ReplacementCall)

        assertNull(gate.onConnection(oldSession, MediaConnectionState.Disconnected))
        assertFalse(gate.complete(oldToken))
        gate.onConnection(replacement, MediaConnectionState.Connected)
        assertEquals(ReplacementCall, requireNotNull(gate.begin(replacement)).callId)

        gate.reset()
        assertNull(gate.begin(replacement))
        assertNull(gate.onConnection(replacement, MediaConnectionState.Connected))
    }

    private companion object {
        const val CurrentCall = "call-1"
        const val ReplacementCall = "call-2"
    }
}
