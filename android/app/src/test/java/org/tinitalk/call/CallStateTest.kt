package org.tinitalk.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CallStateTest {
    @Test
    fun allowsExpectedTransitionsAndRejectsInvalidOnes() {
        val machine = CallStateMachine()

        machine.transition(CallPhase.Ringing, "call-a")
        machine.transition(CallPhase.Connecting, "call-a")
        machine.transition(CallPhase.Active, "call-a")
        machine.transition(CallPhase.Ended, "call-a")

        assertEquals(CallPhase.Ended, machine.snapshot().phase)
        val invalid = runCatching { machine.transition(CallPhase.Active, "call-b") }
        assertTrue(invalid.isFailure)
    }
}
