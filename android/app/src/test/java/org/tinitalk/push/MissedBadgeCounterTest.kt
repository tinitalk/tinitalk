package org.tinitalk.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MissedBadgeCounterTest {
    @Test
    fun ignoresAStaleCountAfterANewerCallWasRecorded() {
        val counter = MissedBadgeCounter()
        val staleRequest = counter.beginRefresh()
        val latestRequest = counter.beginRefresh()

        assertTrue(counter.update(latestRequest, count = 2).applied)
        val stale = counter.update(staleRequest, count = 0)

        assertFalse(stale.applied)
        assertEquals(2, stale.count)
    }

    @Test
    fun latePreMarkResponseCannotRestoreAReadBadge() {
        val counter = MissedBadgeCounter()
        val beforeMark = counter.beginRefresh()
        val afterMark = counter.beginRefresh()

        assertEquals(0, counter.update(afterMark, count = 0).count)
        val late = counter.update(beforeMark, count = 2)

        assertFalse(late.applied)
        assertEquals(0, late.count)
    }

    @Test
    fun failedNewerRefreshStillInvalidatesAnOlderResponse() {
        val counter = MissedBadgeCounter()
        val oldRequest = counter.beginRefresh()
        counter.beginRefresh()

        assertFalse(counter.update(oldRequest, count = 3).applied)
        assertEquals(0, counter.update(oldRequest, count = 3).count)
    }

    @Test
    fun defersUpdatedCountPublication() {
        val pending = ArrayDeque<() -> Unit>()
        val published = mutableListOf<Int>()
        val badges = MissedBadgeUpdater(MissedBadgeCounter()) { task -> pending.addLast(task) }
        val refreshId = badges.beginRefresh()

        assertEquals(3, badges.update(refreshId, count = 3, publish = published::add).count)
        assertTrue(published.isEmpty())

        pending.removeFirst().invoke()

        assertEquals(listOf(3), published)
    }

    @Test
    fun acceptedRefreshNotifiesRegisteredObserverImmediately() {
        val pending = ArrayDeque<() -> Unit>()
        val observed = mutableListOf<Int>()
        val badges = MissedBadgeUpdater(MissedBadgeCounter()) { task -> pending.addLast(task) }
        val observer: (Int) -> Unit = observed::add
        badges.observe(observer)

        val refreshId = badges.beginRefresh()
        badges.update(refreshId, count = 3, publish = {})

        assertEquals(listOf(0, 3), observed)
        assertEquals(1, pending.size)
        badges.removeObserver(observer)
    }
}
