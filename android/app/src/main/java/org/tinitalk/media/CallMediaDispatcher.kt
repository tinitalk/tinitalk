package org.tinitalk.media

import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

internal class CallMediaDispatcher : AutoCloseable {
    private val closed = AtomicBoolean(false)
    @Volatile private var worker: Thread? = null
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, ThreadName).apply { isDaemon = true }.also { worker = it }
    }

    fun dispatch(task: () -> Unit): Boolean {
        if (closed.get()) return false
        return try {
            executor.execute(task)
            true
        } catch (_: RejectedExecutionException) {
            // close() may win between the closed check and executor submission.
            false
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) executor.shutdown()
    }

    fun describeWorker(): String = worker?.let { thread ->
        "${thread.name} (${thread.state})\n" + thread.stackTrace.joinToString("\n") { "  at $it" }
    } ?: "media worker not started"

    private companion object {
        const val ThreadName = "TiniTalkCallMedia"
    }
}
