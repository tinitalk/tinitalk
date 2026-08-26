package org.tinitalk.telecom

import org.tinitalk.call.CallDirection
import org.tinitalk.call.CallPhase
import org.tinitalk.call.CallUiState
import org.tinitalk.call.ConnectionHealth
import org.junit.Assert.assertEquals
import org.junit.Test

class CallToneModeTest {
    @Test
    fun selectsSoundsForDeliveryReconnectAndCompletedConversation() {
        assertEquals(
            CallToneMode.Reaching,
            callToneMode(CallUiState(direction = CallDirection.Outgoing, phase = CallPhase.Connecting)),
        )
        assertEquals(
            CallToneMode.Reconnecting,
            callToneMode(CallUiState(phase = CallPhase.Active, connectedAtElapsedMs = 1L, connectionHealth = ConnectionHealth.Reconnecting)),
        )
        assertEquals(
            CallToneMode.Ended,
            callToneMode(CallUiState(phase = CallPhase.Ended, connectedAtElapsedMs = 1L)),
        )
        assertEquals(
            CallToneMode.Silent,
            callToneMode(CallUiState(phase = CallPhase.Ended)),
        )
    }
}
