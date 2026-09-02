package org.tinitalk.push

import org.tinitalk.data.AccountId
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MissedBadgeCounterTest {
    @Test
    fun missedRedialRequiresItsAccountIdentity() {
        assertTrue(shouldOfferMissedRedial("sam", true))
        assertFalse(shouldOfferMissedRedial("sam", false))
    }

    @Test
    fun persistedCountsHydrateOnlyActiveAccounts() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val preferences = context.getSharedPreferences("badge-test", android.content.Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val store = AccountMissedBadgeStore(preferences)
        val a = AccountId("persist-a")
        val b = AccountId("persist-b")
        store.save(mapOf(a to 2, b to 4))
        val counter = AccountMissedBadgeCounter()
        assertEquals(4, counter.sync(listOf(b), store.load()))
        assertEquals(mapOf(b to 4), counter.snapshot())
    }

    @Test
    fun persistedSyncWritesTheSameFilteredSnapshotItHydrates() {
        val a = AccountId("persist-a")
        val b = AccountId("persist-b")
        val saved = mutableListOf<Map<AccountId, Int>>()
        val updater = AccountMissedBadgeUpdater(AccountMissedBadgeCounter()) {}

        assertEquals(4, updater.syncPersisted(listOf(b), { mapOf(a to 9, b to 4) }, saved::add))

        assertEquals(listOf(mapOf(b to 4)), saved)
        assertEquals(mapOf(b to 4), updater.snapshot())
    }

    @Test
    fun accountCountsAggregateAndStaleOrRemovedAccountCannotResurrect() {
        val a = AccountId("a")
        val b = AccountId("b")
        val counter = AccountMissedBadgeCounter()
        counter.sync(listOf(a, b))
        val staleA = counter.beginRefresh(a)
        val freshA = counter.beginRefresh(a)
        val freshB = counter.beginRefresh(b)

        assertEquals(2, counter.update(a, freshA, 2).count)
        assertEquals(5, counter.update(b, freshB, 3).count)
        assertFalse(counter.update(b, freshA, 9).applied)
        assertFalse(counter.update(a, staleA, 0).applied)
        counter.remove(a)
        assertFalse(counter.update(a, freshA, 9).applied)
        assertEquals(mapOf(b to 3), counter.snapshot())
    }

    @Test
    fun autoMarkTokenSupersedesHistoryFetchAndYieldsToALaterPush() {
        val account = AccountId("a")
        val counter = AccountMissedBadgeCounter()
        counter.sync(listOf(account))
        val historyFetch = counter.beginRefresh(account)
        val autoMark = counter.beginRefresh(account)

        assertFalse(counter.update(account, historyFetch, 3).applied)
        val laterPush = counter.beginRefresh(account)
        assertFalse(counter.update(account, autoMark, 0).applied)
        assertEquals(2, counter.update(account, laterPush, 2).count)
    }

    @Test
    fun queuedOlderPublishIsSuppressedAndInitialCountsHydrate() {
        val a = AccountId("a")
        val b = AccountId("b")
        val pending = ArrayDeque<() -> Unit>()
        val published = mutableListOf<Int>()
        val updater = AccountMissedBadgeUpdater(AccountMissedBadgeCounter()) { pending.addLast(it) }
        updater.sync(listOf(a, b), mapOf(b to 4))
        val aRefresh = updater.beginRefresh(a)
        updater.update(a, aRefresh, 2, {}, published::add)
        val bRefresh = updater.beginRefresh(b)
        updater.updateImmediately(b, bRefresh, 1, {}, published::add)

        pending.removeFirst().invoke()
        assertEquals(listOf(3), published)
    }

    @Test
    fun latestPublishReconcilesEveryAccountWhoseEarlierPublishWasSuppressed() {
        val a = AccountId("a")
        val b = AccountId("b")
        val pending = ArrayDeque<() -> Unit>()
        val published = mutableListOf<Pair<Int, Set<AccountId>>>()
        val updater = AccountMissedBadgeUpdater(AccountMissedBadgeCounter()) { pending.addLast(it) }
        val publish: (Int) -> Unit = { count ->
            val accounts = updater.pendingReconcileAccounts()
            published += count to accounts
            updater.markReconciled(accounts)
        }
        updater.sync(listOf(a, b), emptyMap())

        updater.update(a, updater.beginRefresh(a), 2, {}, publish)
        updater.updateImmediately(b, updater.beginRefresh(b), 1, {}, publish)
        pending.removeFirst().invoke()

        assertEquals(listOf(3 to setOf(a, b)), published)
        assertTrue(updater.pendingReconcileAccounts().isEmpty())
    }

    @Test
    fun persistedSyncReplacesThePublicationThatItInvalidates() {
        val account = AccountId("account")
        val pending = ArrayDeque<() -> Unit>()
        val published = mutableListOf<Pair<Int, Set<AccountId>>>()
        val updater = AccountMissedBadgeUpdater(AccountMissedBadgeCounter()) { pending.addLast(it) }
        val publish: (Int) -> Unit = { count ->
            val accounts = updater.pendingReconcileAccounts()
            published += count to accounts
            updater.markReconciled(accounts)
        }
        updater.sync(listOf(account), emptyMap())
        updater.update(account, updater.beginRefresh(account), 1, {}, publish)
        updater.syncPersisted(listOf(account), { mapOf(account to 1) }, {}, publish)

        pending.removeFirst().invoke()
        pending.removeFirst().invoke()

        assertEquals(listOf(1 to setOf(account)), published)
    }

}
