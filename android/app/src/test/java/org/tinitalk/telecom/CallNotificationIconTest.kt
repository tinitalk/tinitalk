package org.tinitalk.telecom

import org.tinitalk.R
import org.tinitalk.call.CallDirection
import org.tinitalk.call.CallPhase
import org.tinitalk.call.CallUiState
import org.tinitalk.call.ConnectionHealth
import org.junit.Assert.assertEquals
import org.junit.Test

class CallNotificationIconTest {
    @Test
    fun `shows distinct icons for dialing active and reconnecting calls`() {
        assertEquals(
            R.drawable.ic_call_ringing,
            callNotificationIcon(
                CallUiState(direction = CallDirection.Incoming, phase = CallPhase.Ringing),
            ),
        )
        assertEquals(
            R.drawable.ic_call_outgoing,
            callNotificationIcon(
                CallUiState(direction = CallDirection.Outgoing, phase = CallPhase.Connecting),
            ),
        )
        assertEquals(
            R.drawable.ic_call_active,
            callNotificationIcon(CallUiState(phase = CallPhase.Active)),
        )
        assertEquals(
            R.drawable.ic_call_reconnecting,
            callNotificationIcon(
                CallUiState(
                    phase = CallPhase.Active,
                    connectionHealth = ConnectionHealth.Reconnecting,
                ),
            ),
        )
    }
}
