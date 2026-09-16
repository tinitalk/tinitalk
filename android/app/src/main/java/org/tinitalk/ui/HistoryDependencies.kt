package org.tinitalk.ui

import org.tinitalk.call.CallSessionBinding
import org.tinitalk.data.AccountCallHistoryPage
import org.tinitalk.data.AccountId
import org.tinitalk.data.AccountRecord
import org.tinitalk.data.AccountUnreadState
import org.tinitalk.data.AuthStore
import org.tinitalk.data.ContactRepository
import org.tinitalk.missed.MissedCallsRefreshId
import org.tinitalk.missed.MissedCallsRepository

internal interface HistoryDataSource {
    fun accounts(): List<AccountRecord>
    fun load(account: AccountRecord, before: Long, limit: Int, peerLogin: String?): AccountCallHistoryPage?
    fun markRead(account: AccountRecord, throughId: Long, peerLogin: String?): AccountUnreadState?
}

internal class RepositoryHistoryDataSource(private val repository: ContactRepository) : HistoryDataSource {
    override fun accounts() = repository.accounts()

    override fun load(account: AccountRecord, before: Long, limit: Int, peerLogin: String?) =
        repository.loadCallHistory(account.id, before, limit, peerLogin, expectedSession = account.session)

    override fun markRead(account: AccountRecord, throughId: Long, peerLogin: String?) =
        repository.markCallHistoryRead(account.id, throughId, peerLogin, expectedSession = account.session)
}

internal interface HistoryBadgeSink {
    fun sync(accounts: List<AccountId>)
    fun begin(accountId: AccountId): MissedCallsRefreshId?
    /** Returns the aggregate count only if both the session and badge generation are still current. */
    fun apply(update: AccountUnreadState, refresh: MissedCallsRefreshId?): Int?
}

internal class MissedCallsHistoryBadgeSink(
    private val missedCalls: MissedCallsRepository,
    private val authStore: AuthStore,
) : HistoryBadgeSink {
    override fun sync(accounts: List<AccountId>) = missedCalls.syncAccounts(accounts)
    override fun begin(accountId: AccountId) = missedCalls.beginRefresh(accountId)

    override fun apply(update: AccountUnreadState, refresh: MissedCallsRefreshId?): Int? {
        val session = update.session ?: authStore.get(update.accountId)?.session ?: return null
        return authStore.withCurrent(update.accountId, session) {
            missedCalls.update(
                update.accountId, update.unread, refresh, redialBinding = CallSessionBinding.from(session),
            ).takeIf { it.applied }?.count
        }
    }
}
