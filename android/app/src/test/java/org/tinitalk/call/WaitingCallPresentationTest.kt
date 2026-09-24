package org.tinitalk.call

import org.junit.Assert.*
import org.junit.Test
import org.tinitalk.data.AccountId
import org.tinitalk.push.IncomingInvite
import java.time.Instant

class WaitingCallPresentationTest {
    private val invite = IncomingInvite(AccountId("one"), CallSessionBinding("https://one.example", "me", "s", null),
        "call", "Caller", Instant.MAX)
    private val pending = WaitingCall(invite, 15_000, 45_000, acknowledged = true)
    private val waiting = WaitingCallsState(listOf(pending))
    private val active = CallUiState(phase = CallPhase.Active, connectedAtElapsedMs = 0L)

    @Test fun onlyConfirmedInvitationsDuringConversationPlayWaitingTone() {
        assertTrue(waitingCallShouldSound(waiting, active, true))
        assertFalse(waitingCallShouldSound(WaitingCallsState(), active, true))
        assertFalse(waitingCallShouldSound(waiting.copy(calls = listOf(pending.copy(acknowledged = false))), active, true))
        assertFalse(waitingCallShouldSound(waiting, active.copy(connectedAtElapsedMs = null), true))
        assertFalse(waitingCallShouldSound(waiting, active.copy(phase = CallPhase.Ended), true))
        assertFalse(waitingCallShouldSound(waiting, active.copy(connectionHealth = ConnectionHealth.Reconnecting), true))
    }

    @Test fun dndAndAnsweringSilenceTheToneWithoutRemovingInvites() {
        assertFalse(waitingCallShouldSound(waiting, active, false))
        assertFalse(waitingCallShouldSound(waiting.copy(answering = invite.owner), active, true))
        assertEquals(1, waiting.calls.size)
    }
}
