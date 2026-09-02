package org.tinitalk.ui

import org.tinitalk.data.CallHistoryItem
import org.tinitalk.data.CallUnreadState
import org.tinitalk.data.AccountContact
import org.tinitalk.data.AccountCallHistoryPage
import org.tinitalk.data.AccountHistory
import org.tinitalk.data.AccountId
import org.tinitalk.data.AccountPeerKey
import org.tinitalk.data.Contact
import org.tinitalk.data.UnreadMissedContact
import org.tinitalk.data.AccountUnreadState
import org.tinitalk.data.Session
import org.tinitalk.acceptsAccountUnreadUpdate
import org.tinitalk.markEachAccountHistoryPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactRefreshStateTest {
    @Test
    fun markReadFailureForAStillMarksB() {
        val a = AccountId("a")
        val b = AccountId("b")
        val pages = listOf(
            AccountCallHistoryPage(a, emptyList(), 0, 1, CallUnreadState(1, emptyList())),
            AccountCallHistoryPage(b, emptyList(), 0, 2, CallUnreadState(2, emptyList())),
        )
        val marked = markEachAccountHistoryPage(pages) { page ->
            if (page.accountId == a) error("A failed")
            else AccountUnreadState(b, CallUnreadState(0, emptyList()))
        }
        assertEquals(listOf(b), marked.map { it.accountId })
    }

    @Test
    fun queuedUnreadFromReplacedSessionIsRejected() {
        val current = Session("https://server", "sam", "new")
        assertFalse(acceptsAccountUnreadUpdate(
            current,
            AccountUnreadState(AccountId("a"), CallUnreadState(1, emptyList()), current.copy(token = "old")),
        ))
    }

    @Test
    fun lengthPrefixedKeysKeepAmbiguousPartsDistinct() {
        assertFalse(accountScopedKey(AccountId("a"), "23") == accountScopedKey(AccountId("a2"), "3"))
    }

    @Test
    fun historyReducerKeepsFailedAccountCacheCursorAndEqualIdsDistinct() {
        val a = AccountId("a")
        val b = AccountId("b")
        val oldB = AccountHistory(b, "https://b.example", history(7, "sam", 1))
        val pageA = AccountCallHistoryPage(
            a, listOf(AccountHistory(a, "https://a.example", history(7, "sam", 2))), 3, 7,
            CallUnreadState(1, listOf(UnreadMissedContact("sam", 2))),
        )

        val result = reduceAccountHistory(
            listOf(a, b), mapOf(b to listOf(oldB)), mapOf(b to 9),
            listOf(pageA), append = false,
        )

        assertEquals(listOf(a, b), result.items.map { it.accountId })
        assertEquals(9L, result.cursors[b])
    }

    @Test
    fun historyWindowHidesBufferedRowsAndIgnoresUnavailableCursor() {
        val available = AccountId("available")
        val unavailable = AccountId("unavailable")
        val loaded = (100L downTo 50L).map { id ->
            AccountHistory(available, "https://available.example", history(id, "sam", id))
        }

        val buffered = accountHistoryWindow(
            loaded = loaded,
            visibleLimit = 50,
            cursors = mapOf(unavailable to 7L),
            unavailableAccounts = setOf(unavailable),
        )
        val fullyVisible = accountHistoryWindow(
            loaded = loaded.take(50),
            visibleLimit = 50,
            cursors = mapOf(unavailable to 7L),
            unavailableAccounts = setOf(unavailable),
        )

        assertEquals(50, buffered.items.size)
        assertTrue(buffered.hasMore)
        assertFalse(fullyVisible.hasMore)
    }

    @Test
    fun accountMergeKeepsEqualLoginsAndUnreadMarkersDistinct() {
        val first = AccountId("first")
        val second = AccountId("second")

        val merged = mergeAccountContacts(
            listOf(first, second),
            mapOf(
                first to listOf(AccountContact(first, "https://first.example", Contact("sam", "Zed"))),
                second to listOf(AccountContact(second, "https://second.example", Contact("sam", "Anna"))),
            ),
        )
        val unread = aggregateUnreadMissed(
            mapOf(
                first to CallUnreadState(1, listOf(UnreadMissedContact("sam", 10))),
                second to CallUnreadState(2, listOf(UnreadMissedContact("sam", 20))),
            ),
        )

        assertEquals(listOf("Anna", "Zed"), merged.map { it.displayName })
        assertEquals(
            mapOf(AccountPeerKey(first, "sam") to 10L, AccountPeerKey(second, "sam") to 20L),
            unread.latestByContact,
        )
    }
    @Test
    fun offlineStartupKeepsStoredSessionWithoutPretendingContactsLoaded() {
        val state = MainScreenState(restoring = true, networkAvailable = true)

        val offline = state.withOfflineSession("https://talk.example.com")

        assertFalse(offline.restoring)
        assertTrue(offline.signedIn)
        assertFalse(offline.networkAvailable)
        assertEquals("https://talk.example.com", offline.serverUrl)
        assertTrue(offline.accountContacts.isEmpty())
    }
}

private fun history(id: Long, login: String, startedAt: Long) = CallHistoryItem(
    id, login, login, "incoming", "completed", true, startedAt, 0,
)
