package org.tinitalk.media

import java.util.concurrent.ScheduledThreadPoolExecutor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskSchedulerTest {
    @Test
    fun cancelledTaskIsRemovedAndExecutorStopsOnClose() {
        val executor = ScheduledThreadPoolExecutor(1)
        val scheduler = ExecutorTaskScheduler(executor)
        var ran = false

        scheduler.schedule(60_000) { ran = true }.cancel()

        assertTrue(executor.queue.isEmpty())
        assertFalse(ran)
        scheduler.close()
        assertTrue(executor.isShutdown)
    }
}
