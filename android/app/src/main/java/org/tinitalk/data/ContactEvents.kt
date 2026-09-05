package org.tinitalk.data

import java.util.concurrent.CopyOnWriteArraySet

internal object ContactEvents {
    private val observers = CopyOnWriteArraySet<(AccountId) -> Unit>()

    fun observe(observer: (AccountId) -> Unit) { observers += observer }

    fun removeObserver(observer: (AccountId) -> Unit) { observers -= observer }

    fun publish(accountId: AccountId) {
        observers.forEach { observer -> runCatching { observer(accountId) } }
    }
}
