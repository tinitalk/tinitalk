package org.tinitalk.ui

import org.tinitalk.data.AccountHistory
import org.tinitalk.data.AccountId
import org.tinitalk.data.AccountPeerKey
import org.tinitalk.data.CallUnreadState

/** History owns this state; MainScreenState is only its presentation projection. */
internal data class MainHistoryState(
    val accountHistory: List<AccountHistory> = emptyList(),
    val historyLoaded: Boolean = false,
    val historyLoading: Boolean = false,
    val historyLoadingMore: Boolean = false,
    val historyNextBefores: Map<AccountId, Long> = emptyMap(),
    val historyVisibleLimit: Int = HISTORY_PAGE_SIZE,
    val historyUnavailableAccounts: Set<AccountId> = emptySet(),
    val historyErrorMessage: String? = null,
    val contactHistory: ContactHistoryState = ContactHistoryState(),
    val unreadMissedCount: Int = 0,
    val unreadByAccount: Map<AccountId, CallUnreadState> = emptyMap(),
) {
    val latestUnreadMissedByAccountContact: Map<AccountPeerKey, Long>
        get() = aggregateUnreadMissed(unreadByAccount).latestByContact
}

internal fun MainScreenState.withHistory(history: MainHistoryState): MainScreenState = copy(
    accountHistory = history.accountHistory,
    historyLoaded = history.historyLoaded,
    historyLoading = history.historyLoading,
    historyLoadingMore = history.historyLoadingMore,
    historyNextBefores = history.historyNextBefores,
    historyVisibleLimit = history.historyVisibleLimit,
    historyUnavailableAccounts = history.historyUnavailableAccounts,
    historyErrorMessage = history.historyErrorMessage,
    contactHistory = history.contactHistory,
    unreadMissedCount = history.unreadMissedCount,
    unreadByAccount = history.unreadByAccount,
    latestUnreadMissedByAccountContact = history.latestUnreadMissedByAccountContact,
)
