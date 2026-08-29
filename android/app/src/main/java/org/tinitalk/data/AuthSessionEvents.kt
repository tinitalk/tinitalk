package org.tinitalk.data

import java.util.concurrent.CopyOnWriteArraySet

data class AuthSessionEvent(val session: Session)

object AuthSessionEvents {
    private val observers = CopyOnWriteArraySet<(AuthSessionEvent) -> Unit>()

    @Volatile
    private var current: AuthSessionEvent? = null

    fun observe(observer: (AuthSessionEvent) -> Unit) {
        observers += observer
        current?.let(observer)
    }

    fun removeObserver(observer: (AuthSessionEvent) -> Unit) {
        observers -= observer
    }

    internal fun publish(event: AuthSessionEvent) {
        current = event
        observers.forEach { it(event) }
    }

    fun clear() {
        current = null
    }
}
