package org.tinitalk.data

import java.util.concurrent.CopyOnWriteArraySet

internal class CallHistoryEventBus {
    private val accountObservers = CopyOnWriteArraySet<(AccountUnreadState) -> Unit>()

    fun observeAccount(observer: (AccountUnreadState) -> Unit) {
        accountObservers += observer
    }

    fun removeAccountObserver(observer: (AccountUnreadState) -> Unit) {
        accountObservers -= observer
    }

    fun publish(unread: AccountUnreadState) {
        accountObservers.forEach { observer -> runCatching { observer(unread) } }
    }
}

internal object CallHistoryEvents {
    private val events = CallHistoryEventBus()

    fun observeAccount(observer: (AccountUnreadState) -> Unit) = events.observeAccount(observer)

    fun removeAccountObserver(observer: (AccountUnreadState) -> Unit) = events.removeAccountObserver(observer)

    fun publish(unread: AccountUnreadState) = events.publish(unread)
}
