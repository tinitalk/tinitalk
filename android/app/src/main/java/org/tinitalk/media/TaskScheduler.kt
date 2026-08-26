package org.tinitalk.media

import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

fun interface CancellableTask {
    fun cancel()
}

interface TaskScheduler {
    fun schedule(delayMillis: Long, action: () -> Unit): CancellableTask
    fun close()
}

internal class ExecutorTaskScheduler internal constructor(
    private val executor: ScheduledThreadPoolExecutor = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "ice-restart-scheduler").apply { isDaemon = true }
    },
) : TaskScheduler {
    init {
        executor.setRemoveOnCancelPolicy(true)
    }

    override fun schedule(delayMillis: Long, action: () -> Unit): CancellableTask {
        val future = executor.schedule(action, delayMillis, TimeUnit.MILLISECONDS)
        return CancellableTask { future.cancel(false) }
    }

    override fun close() {
        executor.shutdownNow()
    }
}
