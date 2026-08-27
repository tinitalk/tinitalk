package org.tinitalk.telecom

import org.tinitalk.media.CancellableTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalSignalGateTest {
    @Test
    fun waitsForSettlementAndCompletesOnlyOnce() {
        val scheduler = FakeTerminalScheduler()
        var completions = 0
        val gate = TerminalSignalGate(20_000L, scheduler::schedule) { completions++ }

        val settle = gate.begin()

        assertEquals(0, completions)
        assertTrue(gate.isWaiting())
        settle()
        settle()
        scheduler.runPending()
        assertEquals(1, completions)
        assertFalse(gate.isWaiting())
        assertTrue(scheduler.cancelled)
    }

    @Test
    fun timeoutCompletesWhenSettlementNeverArrives() {
        val scheduler = FakeTerminalScheduler()
        var completions = 0
        val gate = TerminalSignalGate(20_000L, scheduler::schedule) { completions++ }

        gate.begin()
        scheduler.runPending()

        assertEquals(1, completions)
        assertEquals(20_000L, scheduler.delayMillis)
    }
}

private class FakeTerminalScheduler {
    private var action: (() -> Unit)? = null
    var delayMillis = 0L
        private set
    var cancelled = false
        private set

    fun schedule(delayMillis: Long, action: () -> Unit): CancellableTask {
        this.delayMillis = delayMillis
        this.action = action
        return CancellableTask { cancelled = true }
    }

    fun runPending() {
        action?.takeUnless { cancelled }?.invoke()
    }
}
