package org.tinitalk.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.tinitalk.data.AccountCallHistoryPage
import org.tinitalk.data.AccountId
import org.tinitalk.data.AccountPeerKey
import org.tinitalk.data.AccountRecord
import org.tinitalk.data.AccountUnreadState
import org.tinitalk.data.ApiException
import org.tinitalk.data.CallHistoryPage
import org.tinitalk.data.Session
import org.tinitalk.data.sameIdentity
import org.tinitalk.push.AccountBadgeRefreshId

internal data class HistoryEnvironment(
    val signedIn: Boolean,
    val networkAvailable: Boolean,
    val resumed: Boolean,
    val sessionGeneration: Int,
)

/**
 * Activity-scoped history owner. All entry points/state updates run on the UI thread;
 * only repository calls run on IO. The supplied lifecycle scope cancels delivery on destroy.
 */
internal class MainHistoryController(
    private val source: HistoryDataSource,
    private val badges: HistoryBadgeSink,
    private val scope: CoroutineScope,
    private val environment: () -> HistoryEnvironment,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val onSessionError: (Throwable, Int) -> Unit = { _, _ -> },
) {
    var state by mutableStateOf(MainHistoryState())
        private set

    private var historyVisible = false
    private var contact: AccountPeerKey? = null
    private var historyGeneration = 0
    private var contactGeneration = 0
    private val historyRefresh = HistoryRefreshGate()
    private val contactRefresh = HistoryRefreshGate()

    fun showContacts() {
        historyVisible = false
    }

    fun showHistory() {
        historyVisible = true
        loadHistory(reset = true, markRead = true)
    }

    fun showContact(key: AccountPeerKey) {
        historyVisible = false
        val displayed = state.contactHistory
        if (contact == key && displayed.accountId == key.accountId && displayed.peerLogin == key.login &&
            (displayed.loaded || displayed.loading)
        ) return
        contactRefresh.clear()
        contact = key
        loadContact(reset = true, markRead = true)
    }

    fun hideContact() {
        contactRefresh.clear()
        contact = null
        contactGeneration++
        state = state.copy(contactHistory = ContactHistoryState())
    }

    fun removeContact(key: AccountPeerKey) {
        if (contact == key) hideContact()
    }

    fun loadMoreHistory() {
        val window = accountHistoryWindow(
            state.accountHistory,
            state.historyVisibleLimit,
            state.historyNextBefores,
            state.historyUnavailableAccounts,
        )
        if (window.hasMore) loadHistory(reset = false)
    }

    fun loadMoreContactHistory() = loadContact(reset = false)

    fun retryContactHistory() = loadContact(
        reset = state.contactHistory.items.isEmpty() || state.contactHistory.nextBefore == 0L,
        markRead = true,
    )

    /** Resume/reconnect refreshes do not implicitly acknowledge new missed calls. */
    fun refreshVisible() {
        when {
            contact != null -> loadContact(reset = true)
            historyVisible -> loadHistory(reset = true)
            else -> refreshMissedCount()
        }
    }

    fun setMissedCount(count: Int) {
        state = state.copy(unreadMissedCount = count)
    }

    fun onOffline() {
        state = state.copy(
            historyLoading = false,
            historyLoadingMore = false,
            contactHistory = state.contactHistory.copy(loading = false, loadingMore = false),
        )
    }

    fun reset(keepHistorySelection: Boolean = false) {
        invalidateLoads()
        contact = null
        if (!keepHistorySelection) historyVisible = false
        state = MainHistoryState()
    }

    /** Invalidates responses without losing already displayed rows or the selected screen. */
    fun invalidateLoads() {
        historyGeneration++
        contactGeneration++
        historyRefresh.clear()
        contactRefresh.clear()
        state = state.copy(
            historyLoading = false,
            historyLoadingMore = false,
            historyErrorMessage = null,
            contactHistory = state.contactHistory.copy(
                loading = false,
                loadingMore = false,
                errorMessage = null,
            ),
        )
    }

    fun removeAccount(accountId: AccountId) {
        if (contact?.accountId == accountId) hideContact()
        state = state.copy(
            accountHistory = state.accountHistory.filterNot { it.accountId == accountId },
            historyNextBefores = state.historyNextBefores - accountId,
            historyUnavailableAccounts = state.historyUnavailableAccounts - accountId,
            unreadByAccount = state.unreadByAccount - accountId,
        )
        badges.sync(source.accounts().map { it.id })
    }

    private fun canLoad(): Boolean = environment().let { it.signedIn && it.networkAvailable } && scope.isActive

    private fun currentSession(generation: Int): Boolean =
        scope.isActive && environment().let { it.signedIn && it.sessionGeneration == generation }

    private fun currentAccount(account: AccountRecord): Boolean =
        source.accounts().any { it.id == account.id && it.session.sameIdentity(account.session) }

    private fun loadHistory(reset: Boolean, markRead: Boolean = false) {
        if (!canLoad() || state.historyLoading || (!reset && state.historyLoadingMore)) return
        val sessionGeneration = environment().sessionGeneration
        val generation = if (reset) ++historyGeneration else historyGeneration
        val targetLimit = if (reset) HISTORY_PAGE_SIZE else state.historyVisibleLimit + HISTORY_PAGE_SIZE
        state = if (reset) {
            state.copy(historyLoading = true, historyErrorMessage = null)
        } else {
            state.copy(historyLoadingMore = true, historyErrorMessage = null)
        }
        val accounts = source.accounts()
        val cursors = state.historyNextBefores
        val cached = state.accountHistory.groupBy { it.accountId }
        val unavailableBefore = state.historyUnavailableAccounts
        val requested = accounts.filter {
            reset || (it.id !in unavailableBefore && (cursors[it.id] ?: 0L) > 0L)
        }
        if (requested.isEmpty()) {
            state = state.copy(historyLoading = false, historyLoadingMore = false, historyVisibleLimit = targetLimit)
            return
        }
        badges.sync(accounts.map { it.id })
        val refreshes = requested.associate { it.id to badges.begin(it.id) }
        scope.launch {
            val pages = requested.map { account ->
                async(ioDispatcher) {
                    val before = if (reset) 0 else cursors[account.id] ?: 0
                    runCatching { source.load(account, before, HISTORY_PAGE_SIZE, null) }.getOrNull()
                }
            }.awaitAll().filterNotNull()
            if (!currentSession(sessionGeneration) || generation != historyGeneration) return@launch
            val active = source.accounts().filter { record ->
                accounts.any { it.id == record.id && it.session.sameIdentity(record.session) }
            }
            val activeOrder = active.map { it.id }
            val activePages = pages.filter { page ->
                active.any { it.id == page.accountId && page.session?.sameIdentity(it.session) == true }
            }
            val requestedIds = requested.filter(::currentAccount).map { it.id }.toSet()
            val successfulIds = activePages.map { it.accountId }.toSet()
            val unavailable = ((if (reset) emptySet() else unavailableBefore) + requestedIds - successfulIds)
                .intersect(activeOrder.toSet())
            val reduced = reduceAccountHistory(activeOrder, cached, cursors, activePages, append = !reset)
            state = state.copy(
                accountHistory = reduced.items,
                historyLoaded = true,
                historyLoading = false,
                historyLoadingMore = false,
                historyNextBefores = reduced.cursors,
                historyVisibleLimit = targetLimit,
                historyUnavailableAccounts = unavailable,
                historyErrorMessage = "Не удалось загрузить историю со всех серверов"
                    .takeIf { reduced.items.isEmpty() && unavailable.isNotEmpty() },
            )
            badges.sync(activeOrder)
            activePages.forEach { applyUnread(it.unreadUpdate(), refreshes[it.accountId]) }
            finishHistoryRefresh()
            if (reset && shouldMarkHistoryRead(markRead, environment().resumed, historyVisible)) {
                markPagesRead(activePages, null, sessionGeneration)
            }
        }
    }

    private fun loadContact(reset: Boolean, markRead: Boolean = false) {
        val key = contact ?: return
        val account = source.accounts().firstOrNull { it.id == key.accountId } ?: return
        if (!canLoad()) return
        val previous = state.contactHistory
        val sameContact = previous.accountId == key.accountId && previous.peerLogin == key.login
        if (reset && sameContact && previous.loading) return
        if (!reset && (!sameContact || previous.nextBefore == 0L || previous.loading || previous.loadingMore)) return
        val generation = if (reset) ++contactGeneration else contactGeneration
        val sessionGeneration = environment().sessionGeneration
        val before = if (reset) 0 else previous.nextBefore
        state = state.copy(
            contactHistory = if (reset) {
                ContactHistoryState(accountId = key.accountId, peerLogin = key.login, loading = true)
            } else {
                previous.copy(loadingMore = true, errorMessage = null)
            },
        )
        val refresh = badges.begin(key.accountId)
        scope.launch {
            val result = withContext(ioDispatcher) {
                runCatching { source.load(account, before, HISTORY_PAGE_SIZE, key.login) }
            }
            if (!currentSession(sessionGeneration) || generation != contactGeneration || contact != key ||
                !currentAccount(account)
            ) return@launch
            val page = result.getOrNull()?.takeIf { it.session?.sameIdentity(account.session) == true }
            if (page != null) {
                applyUnread(page.unreadUpdate(), refresh)
                val raw = CallHistoryPage(
                    page.items.map { it.item }, page.nextBefore, page.latestId,
                    page.unread.unreadMissedCount, page.unread.unreadMissed,
                )
                state = state.copy(contactHistory = state.contactHistory.withPage(key.login, raw, reset))
                if (reset && shouldMarkHistoryRead(markRead, environment().resumed, contact == key)) {
                    markPagesRead(listOf(page), key.login, sessionGeneration)
                }
            } else {
                state = state.copy(
                    contactHistory = state.contactHistory.copy(
                        loaded = true,
                        loading = false,
                        loadingMore = false,
                        errorMessage = "Не удалось загрузить звонки. Проверьте соединение.",
                    ),
                )
                result.exceptionOrNull()?.let { error ->
                    if (error is ApiException && error.code == 401) onSessionError(error, sessionGeneration)
                }
            }
            finishContactRefresh(key)
        }
    }

    private fun markPagesRead(
        pages: List<AccountCallHistoryPage>,
        peerLogin: String?,
        sessionGeneration: Int,
    ) {
        if (!canLoad()) return
        val readable = pages.filter { it.latestId > 0 && it.session != null }
        val refreshes = readable.associate { it.accountId to badges.begin(it.accountId) }
        scope.launch {
            val updates = withContext(ioDispatcher) {
                markEachAccountHistoryPage(readable) { page ->
                    source.markRead(AccountRecord(page.accountId, checkNotNull(page.session)), page.latestId, peerLogin)
                }
            }
            if (!currentSession(sessionGeneration)) return@launch
            updates.forEach { applyUnread(it, refreshes[it.accountId]) }
        }
    }

    fun refreshMissedCount() {
        if (!canLoad()) return
        val sessionGeneration = environment().sessionGeneration
        val generation = historyGeneration
        val accounts = source.accounts()
        badges.sync(accounts.map { it.id })
        val refreshes = accounts.associate { it.id to badges.begin(it.id) }
        scope.launch {
            val pages = withContext(ioDispatcher) {
                accounts.mapNotNull { runCatching { source.load(it, 0, 1, null) }.getOrNull() }
            }
            if (!currentSession(sessionGeneration) || generation != historyGeneration) return@launch
            pages.forEach { applyUnread(it.unreadUpdate(), refreshes[it.accountId]) }
        }
    }

    fun onHistoryChanged(update: AccountUnreadState) {
        val account = source.accounts().firstOrNull { it.id == update.accountId } ?: return
        if (!acceptsAccountUnreadUpdate(account.session, update)) return
        badges.sync(source.accounts().map { it.id })
        if (!applyUnread(update, badges.begin(update.accountId))) return
        if (!canLoad() || !environment().resumed) return
        when {
            contact != null -> if (contactRefresh.request(state.contactHistory.loading || state.contactHistory.loadingMore)) {
                loadContact(reset = true)
            }
            historyVisible -> if (historyRefresh.request(state.historyLoading || state.historyLoadingMore)) {
                loadHistory(reset = true)
            }
        }
    }

    private fun applyUnread(update: AccountUnreadState, refresh: AccountBadgeRefreshId?): Boolean {
        val account = source.accounts().firstOrNull { it.id == update.accountId } ?: return false
        if (!acceptsAccountUnreadUpdate(account.session, update)) return false
        badges.sync(source.accounts().map { it.id })
        val count = badges.apply(update, refresh) ?: return false
        state = state.copy(
            unreadByAccount = state.unreadByAccount + (update.accountId to update.unread),
            unreadMissedCount = count,
        )
        return true
    }

    private fun finishHistoryRefresh() {
        if (historyRefresh.afterLoad() && canLoad() && isHistoryVisibleToUser(environment().resumed, historyVisible)) {
            loadHistory(reset = true)
        }
    }

    private fun finishContactRefresh(key: AccountPeerKey) {
        if (contactRefresh.afterLoad() && canLoad() && isHistoryVisibleToUser(environment().resumed, contact == key)) {
            loadContact(reset = true)
        }
    }
}

private fun AccountCallHistoryPage.unreadUpdate() = AccountUnreadState(accountId, unread, session)

internal fun acceptsAccountUnreadUpdate(currentSession: Session, update: AccountUnreadState): Boolean =
    update.session == null || update.session.sameIdentity(currentSession)

internal fun markEachAccountHistoryPage(
    pages: List<AccountCallHistoryPage>,
    mark: (AccountCallHistoryPage) -> AccountUnreadState?,
): List<AccountUnreadState> = pages.mapNotNull { page -> runCatching { mark(page) }.getOrNull() }
