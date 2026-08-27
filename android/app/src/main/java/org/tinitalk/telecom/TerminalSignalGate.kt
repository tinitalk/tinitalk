package org.tinitalk.telecom

import org.tinitalk.media.CancellableTask

internal class TerminalSignalGate(
    private val timeoutMillis: Long,
    private val scheduleTimeout: (Long, () -> Unit) -> CancellableTask,
    private val onReady: () -> Unit,
) {
    private var waiting = false
    private var timeoutTask: CancellableTask? = null

    fun begin(): () -> Unit {
        val settle = { complete() }
        synchronized(this) {
            check(!waiting) { "already waiting for terminal signal" }
            waiting = true
        }
        val scheduled = scheduleTimeout(timeoutMillis, settle)
        synchronized(this) {
            if (waiting) {
                timeoutTask = scheduled
            } else {
                scheduled.cancel()
            }
        }
        return settle
    }

    fun close() {
        val task = synchronized(this) {
            waiting = false
            timeoutTask.also { timeoutTask = null }
        }
        task?.cancel()
    }

    fun isWaiting(): Boolean = synchronized(this) { waiting }

    private fun complete() {
        val task = synchronized(this) {
            if (!waiting) return
            waiting = false
            timeoutTask.also { timeoutTask = null }
        }
        task?.cancel()
        onReady()
    }
}
