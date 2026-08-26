package org.tinitalk.call

import org.junit.Assert.assertEquals
import org.junit.Test

class CallServiceStateTest {
    @Test
    fun completedCallReturnsPublishedStateToIdle() {
        CallServiceState.publish(CallSnapshot(CallPhase.Ended, "call-1", 4))

        CallServiceState.reset()

        assertEquals(CallSnapshot(), CallServiceState.snapshot())
    }
}
