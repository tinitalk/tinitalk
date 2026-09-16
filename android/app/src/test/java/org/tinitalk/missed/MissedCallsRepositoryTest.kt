package org.tinitalk.missed

import org.junit.Assert.*
import org.junit.Test
import org.tinitalk.data.AccountId
import org.tinitalk.data.CallUnreadState
import org.tinitalk.data.UnreadMissedContact

class MissedCallsRepositoryTest {
    private val a = AccountId("a")
    private val b = AccountId("b")

    @Test fun countsPersistAndReachUiWithoutAnyNotificationRenderer() {
        val storage = MemoryCounts()
        val repository = MissedCallsRepository(storage, { it() })
        val observed = mutableListOf<Int>()
        repository.observeCount(observed::add)
        repository.syncAccounts(listOf(a, b))
        repository.update(a, unread(2), repository.beginRefresh(a))
        repository.update(b, unread(3), repository.beginRefresh(b))

        assertEquals(mapOf(a to 2, b to 3), storage.counts)
        assertEquals(5, observed.last())
        assertEquals(5, repository.snapshot().totalCount)
        assertEquals(setOf(a, b), repository.snapshot().targets.map { it.accountId }.toSet())
        assertEquals("Боб", repository.snapshot().targets.first().name)
    }

    @Test fun readingOneAccountDoesNotEraseAnotherOrAcceptOlderResponse() {
        val repository = MissedCallsRepository(MemoryCounts(), { it() })
        repository.syncAccounts(listOf(a, b))
        repository.update(b, unread(3), repository.beginRefresh(b))
        val old = repository.beginRefresh(a)
        repository.update(a, unread(0), repository.beginRefresh(a))
        assertFalse(repository.update(a, unread(2), old).applied)
        assertEquals(mapOf(a to 0, b to 3), repository.snapshot().counts)
    }

    @Test fun provisionalPushPreservesOtherContactsUntilAuthoritativeHistoryArrives() {
        val repository = MissedCallsRepository(MemoryCounts(), { it() })
        repository.syncAccounts(listOf(a))
        repository.update(a, unread(2), repository.beginRefresh(a))
        repository.recordMissedIfAbsent(a, MissedCallTarget(20, a, "alice", "Аня", null, 1))
        assertEquals(setOf("bob", "alice"), repository.snapshot().targets.map { it.login }.toSet())
        repository.update(a, unread(0), repository.beginRefresh(a))
        assertTrue(repository.snapshot().targets.isEmpty())
        assertEquals(0, repository.snapshot().totalCount)
    }

    @Test fun queuedPublicationCannotResurrectRemovedAccount() {
        val pending = ArrayDeque<() -> Unit>()
        val published = mutableListOf<MissedCallsSnapshot>()
        val repository = MissedCallsRepository(MemoryCounts(), pending::addLast, published::add)
        repository.syncAccounts(listOf(a, b))
        val old = repository.beginRefresh(a)
        repository.update(a, unread(2), old)
        repository.update(b, unread(1), repository.beginRefresh(b))
        repository.removeAccount(a)
        while (pending.isNotEmpty()) pending.removeFirst().invoke()

        assertEquals(1, published.size)
        assertEquals(mapOf(b to 1), published.single().counts)
        assertEquals(setOf(a, b), published.single().reconcileAccounts)
        assertFalse(repository.update(a, unread(4), old).applied)
    }

    @Test fun provisionalPushWithoutCallerDoesNotEraseKnownMissedContacts() {
        val repository = MissedCallsRepository(MemoryCounts(), { it() })
        repository.syncAccounts(listOf(a))
        repository.update(a, unread(2), repository.beginRefresh(a))

        repository.recordMissedIfAbsent(a, null)

        assertEquals(2, repository.snapshot().totalCount)
        assertEquals(listOf("bob"), repository.snapshot().targets.map { it.login })
    }

    @Test fun startupRestoresOnlyCountsForActiveAccounts() {
        val storage = MemoryCounts(mapOf(a to 2, b to 4))
        val repository = MissedCallsRepository(storage, { it() })
        repository.syncAccounts(listOf(b))

        assertEquals(mapOf(b to 4), repository.snapshot().counts)
        assertEquals(mapOf(b to 4), storage.counts)
        assertTrue(repository.snapshot().targets.isEmpty())
    }

    private fun unread(count: Int) = CallUnreadState(
        count,
        if (count == 0) emptyList() else listOf(UnreadMissedContact("bob", 10, "Боб", count)),
    )

    private class MemoryCounts(var counts: Map<AccountId, Int> = emptyMap()) : MissedCallsPersistence {
        override fun load() = counts
        override fun save(counts: Map<AccountId, Int>) { this.counts = counts.toMap() }
    }
}
