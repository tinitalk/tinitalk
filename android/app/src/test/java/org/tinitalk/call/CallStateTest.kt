package org.tinitalk.call

import org.tinitalk.data.AccountId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CallStateTest {
    @Test
    fun allowsExpectedTransitionsAndRejectsInvalidOnes() {
        val accountId = AccountId("account-b")
        val machine = CallStateMachine(accountId)

        machine.transition(CallPhase.Ringing, "call-a")
        machine.transition(CallPhase.Connecting, "call-a")
        machine.transition(CallPhase.Active, "call-a")
        machine.transition(CallPhase.Ended, "call-a")

        assertEquals(CallPhase.Ended, machine.snapshot().phase)
        assertEquals(AccountCallKey(accountId, "call-a"), machine.snapshot().callKey)
        val invalid = runCatching { machine.transition(CallPhase.Active, "call-b") }
        assertTrue(invalid.isFailure)

        machine.reset()
        machine.transition(CallPhase.Connecting, "call-b")
        assertEquals(CallPhase.Connecting, machine.snapshot().phase)
        assertEquals(AccountCallKey(accountId, "call-b"), machine.snapshot().callKey)
    }
}
