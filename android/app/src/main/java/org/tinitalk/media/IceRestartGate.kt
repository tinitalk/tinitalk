package org.tinitalk.media

internal class IceRestartGate(private val scheduler: TaskScheduler) {
    private var generation = 0L
    private var scheduleId = 0L
    private var outage = false
    private var attempted = false
    private var closed = false
    private var pending: CancellableTask? = null
    private var pendingDelayMillis: Long? = null

    @Synchronized
    fun onDisconnected(onRestart: () -> Unit) {
        if (closed || outage) return
        startOutage(DisconnectedDelayMillis, onRestart)
    }

    @Synchronized
    fun onFailed(onRestart: () -> Unit) {
        if (closed) return
        if (!outage) {
            startOutage(FailedDelayMillis, onRestart)
        } else if (!attempted && pendingDelayMillis != FailedDelayMillis) {
            scheduleLocked(FailedDelayMillis, onRestart, generation)
        }
    }

    @Synchronized
    fun onConnected() {
        if (closed) return
        outage = false
        attempted = false
        generation++
        scheduleId++
        pending?.cancel()
        pending = null
        pendingDelayMillis = null
    }

    private fun startOutage(delayMillis: Long, onRestart: () -> Unit) {
        if (outage) return
        outage = true
        attempted = false
        val expectedGeneration = ++generation
        scheduleLocked(delayMillis, onRestart, expectedGeneration)
    }

    private fun scheduleLocked(delayMillis: Long, onRestart: () -> Unit, expectedGeneration: Long) {
        pending?.cancel()
        val expectedScheduleId = ++scheduleId
        pendingDelayMillis = delayMillis
        pending = scheduler.schedule(delayMillis) {
            val fire = synchronized(this) {
                if (!closed && outage && generation == expectedGeneration && scheduleId == expectedScheduleId) {
                    pending = null
                    pendingDelayMillis = null
                    attempted = true
                    true
                } else {
                    false
                }
            }
            if (fire) {
                try {
                    onRestart()
                } finally {
                    synchronized(this) {
                        if (!closed && outage && generation == expectedGeneration && pending == null) {
                            scheduleLocked(RetryDelayMillis, onRestart, expectedGeneration)
                        }
                    }
                }
            }
        }
    }

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        generation++
        scheduleId++
        pending?.cancel()
        pending = null
        pendingDelayMillis = null
        scheduler.close()
    }

    private companion object {
        const val DisconnectedDelayMillis = 1_000L
        const val FailedDelayMillis = 0L
        const val RetryDelayMillis = 10_000L
    }
}
