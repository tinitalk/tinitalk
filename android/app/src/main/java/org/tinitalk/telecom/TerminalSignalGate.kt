package org.tinitalk.telecom

import org.tinitalk.media.CancellableTask

internal class TerminalSignalGate(
    private val timeoutMillis: Long,
    private val scheduleTimeout: (Long, () -> Unit) -> CancellableTask,
) {
    private var waiting = false
    private var timeoutTask: CancellableTask? = null
    private var readyCallback: (() -> Unit)? = null
    private var nextToken = 0L
    private var activeToken: Long? = null

    fun begin(onReady: () -> Unit): () -> Unit {
        val token = synchronized(this) {
            check(!waiting) { "already waiting for terminal signal" }
            waiting = true
            readyCallback = onReady
            (++nextToken).also { activeToken = it }
        }
        val settle = { complete(token) }
        val scheduled = scheduleTimeout(timeoutMillis, settle)
        synchronized(this) {
            if (waiting && activeToken == token) {
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
            readyCallback = null
            activeToken = null
            timeoutTask.also { timeoutTask = null }
        }
        task?.cancel()
    }

    fun isWaiting(): Boolean = synchronized(this) { waiting }

    private fun complete(token: Long) {
        val (task, callback) = synchronized(this) {
            if (!waiting || activeToken != token) return
            waiting = false
            activeToken = null
            val timeout = timeoutTask.also { timeoutTask = null }
            val ready = readyCallback.also { readyCallback = null }
            timeout to ready
        }
        task?.cancel()
        callback?.invoke()
    }
}
