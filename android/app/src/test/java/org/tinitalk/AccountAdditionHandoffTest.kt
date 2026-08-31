package org.tinitalk

import org.tinitalk.data.AccountContactPage
import org.tinitalk.data.AccountId
import org.junit.Assert.assertEquals
import org.junit.Test

class AccountAdditionHandoffTest {
    @Test
    fun replacementObserverDrainsQueuedOutcomesPublishedForPreviousActivityExactlyOnce() {
        val handoff = AccountAdditionHandoff()
        val success = AccountAdditionOutcome.Added(
            accountId = AccountId("account-b"),
            sessionId = "session-b",
            configId = "config-b",
            contacts = AccountContactPage(AccountId("account-b"), emptyList(), ""),
        )
        val failure = AccountAdditionOutcome.Failed("Неверный логин или токен")
        var previousActivitySignals = 0
        val previousActivityObserver: () -> Unit = { previousActivitySignals++ }
        handoff.observe(previousActivityObserver)

        handoff.publish(success)
        handoff.publish(failure)
        assertEquals(2, previousActivitySignals)
        handoff.removeObserver(previousActivityObserver)

        var replacementActivitySignals = 0
        handoff.observe { replacementActivitySignals++; Unit }

        assertEquals(1, replacementActivitySignals)
        assertEquals(listOf(success, failure), handoff.drain())
        assertEquals(emptyList<AccountAdditionOutcome>(), handoff.drain())
    }
}
