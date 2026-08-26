package org.tinitalk.media

import org.junit.Assert.assertEquals
import org.junit.Test

class IceRestartGateTest {
    @Test
    fun disconnectedWaitsOneSecondAndRecoveryCancelsTheAttempt() {
        val scheduler = FakeTaskScheduler()
        var restarts = 0
        val gate = IceRestartGate(scheduler)

        gate.onDisconnected { restarts++ }

        assertEquals(listOf(1_000L), scheduler.pendingDelays)

        gate.onConnected()
        scheduler.runNext()

        assertEquals(0, restarts)
        assertEquals(0, scheduler.pendingTaskCount)
    }

    @Test
    fun failedConnectionRestartsImmediately() {
        val scheduler = FakeTaskScheduler()
        var restarts = 0
        val gate = IceRestartGate(scheduler)

        gate.onDisconnected { restarts++ }
        gate.onFailed { restarts++ }

        assertEquals(listOf(0L), scheduler.pendingDelays)

        scheduler.runNext()

        assertEquals(1, restarts)
        assertEquals(listOf(10_000L), scheduler.pendingDelays)
    }

    @Test
    fun persistentOutageRetriesEveryTenSecondsWithoutDuplicateTasks() {
        val scheduler = FakeTaskScheduler()
        var restarts = 0
        val gate = IceRestartGate(scheduler)

        gate.onDisconnected { restarts++ }
        gate.onDisconnected { restarts++ }
        scheduler.runNext()

        assertEquals(1, restarts)
        assertEquals(listOf(10_000L), scheduler.pendingDelays)

        scheduler.runNext()

        assertEquals(2, restarts)
        assertEquals(listOf(10_000L), scheduler.pendingDelays)
    }

    @Test
    fun recoveryAfterRestartCancelsTheRetryAndRearmsNextOutage() {
        val scheduler = FakeTaskScheduler()
        var restarts = 0
        val gate = IceRestartGate(scheduler)

        gate.onDisconnected { restarts++ }
        scheduler.runNext()
        gate.onConnected()

        assertEquals(0, scheduler.pendingTaskCount)

        gate.onDisconnected { restarts++ }
        scheduler.runNext()

        assertEquals(2, restarts)
    }

    @Test
    fun closeCancelsPendingRestartAndIsIdempotent() {
        val scheduler = FakeTaskScheduler()
        var restarts = 0
        val gate = IceRestartGate(scheduler)

        gate.onDisconnected { restarts++ }
        gate.close()
        gate.close()

        assertEquals(0, scheduler.pendingTaskCount)
        assertEquals(1, scheduler.closeCalls)
        scheduler.runNext()
        assertEquals(0, restarts)
    }

    private class FakeTaskScheduler : TaskScheduler {
        private val tasks = mutableListOf<FakeTask>()
        var closeCalls = 0
        val pendingTaskCount: Int get() = tasks.count { !it.cancelled && !it.ran }
        val pendingDelays: List<Long> get() = tasks.filter { !it.cancelled && !it.ran }.map { it.delayMillis }

        override fun schedule(delayMillis: Long, action: () -> Unit): CancellableTask =
            FakeTask(delayMillis, action).also(tasks::add)

        override fun close() {
            closeCalls++
        }

        fun runNext() {
            tasks.firstOrNull { !it.cancelled && !it.ran }?.run()
        }

        private class FakeTask(
            val delayMillis: Long,
            private val action: () -> Unit,
        ) : CancellableTask {
            var cancelled = false
            var ran = false

            fun run() {
                ran = true
                action()
            }

            override fun cancel() {
                cancelled = true
            }
        }
    }
}
