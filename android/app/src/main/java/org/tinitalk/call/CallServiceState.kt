package org.tinitalk.call

import java.util.concurrent.CopyOnWriteArraySet

object CallServiceState {
    private val listeners = CopyOnWriteArraySet<(CallSnapshot) -> Unit>()

    @Volatile
    private var current = CallSnapshot()

    fun snapshot(): CallSnapshot = current

    fun observe(listener: (CallSnapshot) -> Unit) {
        listeners += listener
        listener(current)
    }

    fun removeObserver(listener: (CallSnapshot) -> Unit) {
        listeners -= listener
    }

    fun publish(snapshot: CallSnapshot) {
        current = snapshot
        listeners.forEach { it(snapshot) }
    }

    fun reset() = publish(CallSnapshot())
}
