package org.tinitalk.media

import java.util.concurrent.atomic.AtomicBoolean

internal class SessionCloseGate {
    private val started = AtomicBoolean(false)

    val closed: Boolean get() = started.get()

    fun runOnce(action: () -> Unit) {
        if (!started.compareAndSet(false, true)) return
        action()
    }
}
