package org.tinitalk.telecom

import org.tinitalk.call.CallDirection
import org.tinitalk.call.CallEndReason
import org.tinitalk.call.CallPhase
import org.tinitalk.call.CallUiState
import org.tinitalk.call.ConnectionHealth
import org.tinitalk.data.signal.SignalFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        assertEquals(
            CallToneMode.Busy,
            callToneMode(CallUiState(phase = CallPhase.Ended, endReason = CallEndReason.Busy)),
        )
    }

    @Test
    fun ignoresFailuresForDiscardedCallId() {
        val busy = SignalFailure("callee already has an active call", "busy", "call-1")

        assertEquals(CallEndReason.Busy, signalingFailureEndReason(busy, "call-1"))
        assertNull(signalingFailureEndReason(busy, "call-2"))
        assertEquals(
            CallEndReason.Failed,
            signalingFailureEndReason(SignalFailure("socket failed"), "call-2"),
        )
    }

    @Test
    fun keepsActiveCallAliveForRetryableIceLimits() {
        assertNull(
            signalingFailureEndReason(
                SignalFailure(
                    message = "ICE restart requested too often",
                    code = "ice_restart_rate_limited",
                    callId = "call-1",
                    eventId = "restart-1",
                    retryAfterMillis = 8_750L,
                ),
                "call-1",
            ),
        )
        assertNull(
            signalingFailureEndReason(
                SignalFailure("too many ICE events", "ice_rate_limited", "call-1"),
                "call-1",
            ),
        )
        assertNull(
            signalingFailureEndReason(
                SignalFailure("ICE restart request sent too often", "ice_restart_request_rate_limited", "call-1"),
                "call-1",
            ),
        )
    }

    @Test
    fun ignoresSignalingFailureWithoutActiveCall() {
        assertNull(signalingFailureEndReason(SignalFailure("call not found"), null))
    }
}
