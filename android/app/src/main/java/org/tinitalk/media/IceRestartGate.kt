package org.tinitalk.media

internal class IceRestartGate(private val scheduler: TaskScheduler) {
    private var generation = 0L
    private var outage = false
    private var closed = false
    private var pending: CancellableTask? = null

    @Synchronized
    fun onState(unavailable: Boolean, onRestart: () -> Unit) {
        if (closed) return
        if (!unavailable) {
            outage = false
            generation++
            pending?.cancel()
            pending = null
            return
        }
        if (outage) return
        outage = true
        val expected = ++generation
        pending = scheduler.schedule(3_000) {
            val fire = synchronized(this) {
                if (!closed && outage && generation == expected) {
                    pending = null
                    true
                } else {
                    false
                }
            }
            if (fire) onRestart()
        }
    }

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        generation++
        pending?.cancel()
        pending = null
        scheduler.close()
    }
}
