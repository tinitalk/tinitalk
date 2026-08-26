package org.tinitalk.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IceRestartGateTest {
    @Test
    fun recoveryBeforeDelayCancelsAttemptAndRearamsNextOutage() {
        val scheduler = FakeTaskScheduler()
        var restarts = 0
        val gate = IceRestartGate(scheduler)

        gate.onState(true) { restarts++ }
        gate.onState(false) { restarts++ }

        assertEquals(0, scheduler.pendingTaskCount)
        scheduler.runPending()
        assertEquals(0, restarts)

        gate.onState(true) { restarts++ }
        scheduler.runPending()

        assertEquals(1, restarts)
    }

    @Test
    fun continuousOutageRequestsOnlyOneRestart() {
        val scheduler = FakeTaskScheduler()
        var restarts = 0
        val gate = IceRestartGate(scheduler)

        gate.onState(true) { restarts++ }
        gate.onState(true) { restarts++ }
        scheduler.runPending()

        assertEquals(1, restarts)
    }

    @Test
    fun closeCancelsPendingRestartAndIsIdempotent() {
        val scheduler = FakeTaskScheduler()
        var restarts = 0
        val gate = IceRestartGate(scheduler)

        gate.onState(true) { restarts++ }
        gate.close()
        gate.close()

        assertEquals(0, scheduler.pendingTaskCount)
        assertEquals(1, scheduler.closeCalls)
        scheduler.runPending()
        assertEquals(0, restarts)
    }

    private class FakeTaskScheduler : TaskScheduler {
        private val tasks = mutableListOf<FakeTask>()
        var closeCalls = 0
        val pendingTaskCount: Int get() = tasks.count { !it.cancelled }

        override fun schedule(delayMillis: Long, action: () -> Unit): CancellableTask =
            FakeTask(action).also(tasks::add)

        override fun close() {
            closeCalls++
        }

        fun runPending() {
            tasks.filterNot { it.cancelled }.forEach { it.action() }
        }

        private class FakeTask(val action: () -> Unit) : CancellableTask {
            var cancelled = false
            override fun cancel() {
                cancelled = true
            }
        }
    }
}
