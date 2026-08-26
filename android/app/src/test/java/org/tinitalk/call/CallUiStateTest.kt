package org.tinitalk.call

import org.tinitalk.media.MediaConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CallUiStateTest {
    @Test
    fun durationStartsOnFirstMediaConnectionAndKeepsItsAnchorAfterReconnect() {
        var state = CallUiState(phase = CallPhase.Active)

        assertNull(state.durationMillis(5_000L))
        state = state.onMediaConnection(MediaConnectionState.Connected, 5_000L)
        state = state.onMediaConnection(MediaConnectionState.Disconnected, 8_000L)
        state = state.onMediaConnection(MediaConnectionState.Connected, 10_000L)

        assertEquals(5_000L, state.connectedAtElapsedMs)
        assertEquals(7_000L, state.durationMillis(12_000L))
        assertEquals(ConnectionHealth.Good, state.connectionHealth)
    }

    @Test
    fun endedCallFreezesConnectedDuration() {
        val connected = CallUiState(phase = CallPhase.Active)
            .onMediaConnection(MediaConnectionState.Connected, 1_000L)

        val ended = connected.onEnded(CallEndReason.RemoteHangup, 61_999L)

        assertEquals(60_999L, ended.durationMillis(500_000L))
        assertEquals("01:00", ended.durationText(500_000L))
    }

    @Test
    fun durationUsesHoursOnlyWhenNeeded() {
        assertEquals("00:00", formatCallDuration(0L))
        assertEquals("04:09", formatCallDuration(249_999L))
        assertEquals("1:04:09", formatCallDuration(3_849_999L))
    }
}
