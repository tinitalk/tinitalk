package org.tinitalk.ui

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import org.tinitalk.data.*
import org.tinitalk.push.AccountBadgeRefreshId
import kotlin.coroutines.CoroutineContext

class MainHistoryControllerTest {
    @Test fun leavingContactDiscardsItsPendingResponse() = with(Fixture()) {
        controller.showContact(AccountPeerKey(account.id, "bob"))
        main.runAll()
        controller.hideContact()
        drain()
        assertEquals(ContactHistoryState(), controller.state.contactHistory)
        assertTrue(reads.isEmpty())
        assertTrue(badges.applied.isEmpty())
    }

    @Test fun replacedSessionCannotPopulateHistoryOrBadges() = with(Fixture()) {
        controller.showHistory()
        main.runAll()
        io.runAll() // The old session's response is now waiting for the UI thread.
        accounts = listOf(account.copy(session = account.session.copy(token = "new")))
        drain()
        assertTrue(controller.state.accountHistory.isEmpty())
        assertTrue(badges.applied.isEmpty())
        assertTrue(reads.isEmpty())
    }

    @Test fun pausingBeforeResponseDoesNotMarkHistoryRead() = with(Fixture()) {
        controller.showHistory()
        main.runAll()
        environment = environment.copy(resumed = false)
        drain()
        assertEquals(listOf(10L), controller.state.accountHistory.map { it.id })
        assertTrue(reads.isEmpty())
    }

    @Test fun explicitVisitMarksReadButBackgroundRefreshDoesNot() = with(Fixture()) {
        controller.showHistory()
        drain()
        assertEquals(listOf(null), reads.map { it.second })
        reads.clear()
        controller.onHistoryChanged(AccountUnreadState(account.id, unread, account.session))
        drain()
        assertTrue(reads.isEmpty())
    }

    @Test fun eventsDuringContactLoadCoalesceIntoOneRefresh() = with(Fixture()) {
        controller.showContact(AccountPeerKey(account.id, "bob"))
        main.runAll()
        repeat(3) { controller.onHistoryChanged(AccountUnreadState(account.id, unread, account.session)) }
        drain()
        assertEquals(listOf("bob", "bob"), loads.map { it.peer })
        assertEquals(listOf("bob"), reads.map { it.second })
        assertFalse(controller.state.contactHistory.loading)
    }

    @Test fun paginationKeepsCachedRowsFromAnUnavailableAccount() = with(Fixture()) {
        val second = account.copy(id = AccountId("second"), session = account.session.copy(url = "https://second"))
        accounts = listOf(account, second)
        controller.showHistory()
        drain()
        failAccount = second.id
        controller.showHistory()
        drain()
        assertEquals(setOf(second.id), controller.state.historyUnavailableAccounts)
        assertEquals(2, controller.state.accountHistory.size)
        loads.clear()
        controller.loadMoreHistory()
        drain()
        assertEquals(listOf(account.id), loads.map { it.account.id })
        assertEquals(5L, loads.single().before)
        assertEquals(3, controller.state.accountHistory.size)
    }

    @Test fun resetAndDestroyedScopeDiscardPendingWork() = with(Fixture()) {
        controller.showHistory()
        main.runAll()
        controller.reset()
        drain()
        assertEquals(MainHistoryState(), controller.state)
        controller.showHistory()
        main.runAll()
        scope.cancel()
        drain()
        assertTrue(controller.state.accountHistory.isEmpty())
        assertTrue(badges.applied.isEmpty())
    }

    @Test fun badgeGenerationRejectionDoesNotReplaceUnreadPresentation() = with(Fixture()) {
        badges.accept = false
        controller.onHistoryChanged(AccountUnreadState(account.id, unread, account.session))
        assertTrue(controller.state.unreadByAccount.isEmpty())
        assertEquals(0, controller.state.unreadMissedCount)
    }

    @Test fun offlineNavigationDoesNotReuseAnotherContactsLoadedState() = with(Fixture()) {
        controller.showContact(AccountPeerKey(account.id, "bob"))
        drain()
        environment = environment.copy(networkAvailable = false)
        controller.onOffline()
        controller.showContact(AccountPeerKey(account.id, "charlie"))
        environment = environment.copy(networkAvailable = true)
        controller.showContact(AccountPeerKey(account.id, "charlie"))
        drain()
        assertEquals("charlie", controller.state.contactHistory.peerLogin)
        assertEquals(listOf("charlie"), controller.state.contactHistory.items.map { it.peerLogin })
    }

    @Test fun offlinePresentationKeepsErrorsAndPreviouslyLoadedHistory() = with(Fixture()) {
        controller.showHistory()
        drain()
        failAccount = account.id
        controller.showContact(AccountPeerKey(account.id, "bob"))
        drain()
        val before = controller.state
        assertNotNull(before.contactHistory.errorMessage)
        environment = environment.copy(networkAvailable = false)
        controller.onOffline()
        assertEquals(before.accountHistory, controller.state.accountHistory)
        assertEquals(before.contactHistory.errorMessage, controller.state.contactHistory.errorMessage)
    }

    private class Fixture : HistoryDataSource {
        val account = AccountRecord(AccountId("first"), Session("https://first", "alice", "token"))
        var accounts = listOf(account)
        val unread = CallUnreadState(1, listOf(UnreadMissedContact("bob", 10)))
        var failAccount: AccountId? = null
        val loads = mutableListOf<Load>()
        val reads = mutableListOf<Pair<Long, String?>>()
        val main = QueueDispatcher()
        val io = QueueDispatcher()
        val scope = CoroutineScope(SupervisorJob() + main)
        var environment = HistoryEnvironment(signedIn = true, networkAvailable = true, resumed = true, sessionGeneration = 1)
        val badges = Badges()
        val controller = MainHistoryController(this, badges, scope, { environment }, io)

        override fun accounts() = accounts
        override fun load(account: AccountRecord, before: Long, limit: Int, peerLogin: String?): AccountCallHistoryPage {
            loads += Load(account, before, peerLogin)
            if (account.id == failAccount) error("offline")
            val id = if (before == 0L) 10L else 4L
            return AccountCallHistoryPage(
                account.id,
                listOf(AccountHistory(account.id, account.session.url,
                    CallHistoryItem(id, peerLogin ?: "bob", "Bob", "incoming", "missed", true, id, 0))),
                if (before == 0L) 5 else 0, 10, unread, account.session,
            )
        }
        override fun markRead(account: AccountRecord, throughId: Long, peerLogin: String?): AccountUnreadState {
            reads += throughId to peerLogin
            return AccountUnreadState(account.id, CallUnreadState(0, emptyList()), account.session)
        }
        fun drain() {
            repeat(20) {
                main.runAll()
                io.runAll()
                if (main.queue.isEmpty() && io.queue.isEmpty()) return
            }
            error("History never became idle")
        }
    }

    private data class Load(val account: AccountRecord, val before: Long, val peer: String?)

    private class QueueDispatcher : CoroutineDispatcher() {
        val queue = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue.addLast(block) }
        fun runAll() { while (queue.isNotEmpty()) queue.removeFirst().run() }
    }

    private class Badges : HistoryBadgeSink {
        var accept = true
        val applied = mutableListOf<AccountUnreadState>()
        override fun sync(accounts: List<AccountId>) = Unit
        override fun begin(accountId: AccountId) = AccountBadgeRefreshId(accountId, 1)
        override fun apply(update: AccountUnreadState, refresh: AccountBadgeRefreshId?): Int? {
            if (!accept) return null
            applied += update
            return update.unread.unreadMissedCount
        }
    }
}
