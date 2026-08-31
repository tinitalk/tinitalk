package org.tinitalk

import org.tinitalk.data.AccountContactPage
import org.tinitalk.data.AccountId
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArraySet

internal sealed interface AccountAdditionOutcome {
    data class Added(
        val accountId: AccountId,
        val sessionId: String?,
        val configId: String?,
        val contacts: AccountContactPage,
    ) : AccountAdditionOutcome

    data class Failed(val message: String) : AccountAdditionOutcome
}

internal class AccountAdditionHandoff {
    private val lock = Any()
    private val observers = CopyOnWriteArraySet<() -> Unit>()
    private val pending = ArrayDeque<AccountAdditionOutcome>()

    fun observe(observer: () -> Unit) {
        observers += observer
        val hasPending = synchronized(lock) { pending.isNotEmpty() }
        if (hasPending) observer()
    }

    fun removeObserver(observer: () -> Unit) {
        observers -= observer
    }

    fun publish(outcome: AccountAdditionOutcome) {
        synchronized(lock) { pending.addLast(outcome) }
        observers.forEach { it() }
    }

    fun drain(): List<AccountAdditionOutcome> = synchronized(lock) {
        pending.toList().also { pending.clear() }
    }
}

internal val accountAdditionHandoff = AccountAdditionHandoff()
