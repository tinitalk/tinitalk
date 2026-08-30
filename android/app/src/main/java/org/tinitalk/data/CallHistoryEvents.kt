package org.tinitalk.data

import java.util.concurrent.CopyOnWriteArraySet

internal class CallHistoryEventBus {
    private val observers = CopyOnWriteArraySet<(CallUnreadState) -> Unit>()

    fun observe(observer: (CallUnreadState) -> Unit) {
        observers += observer
    }

    fun removeObserver(observer: (CallUnreadState) -> Unit) {
        observers -= observer
    }

    fun publish(unread: CallUnreadState) {
        observers.forEach { observer -> runCatching { observer(unread) } }
    }
}

internal object CallHistoryEvents {
    private val events = CallHistoryEventBus()

    fun observe(observer: (CallUnreadState) -> Unit) = events.observe(observer)

    fun removeObserver(observer: (CallUnreadState) -> Unit) = events.removeObserver(observer)

    fun publish(unread: CallUnreadState) = events.publish(unread)
}
