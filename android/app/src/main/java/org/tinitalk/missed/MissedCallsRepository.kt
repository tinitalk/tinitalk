package org.tinitalk.missed

import org.tinitalk.call.CallSessionBinding
import org.tinitalk.data.AccountId
import org.tinitalk.data.CallUnreadState
import org.tinitalk.data.UnreadMissedContact

internal interface MissedCallsPersistence {
    fun load(): Map<AccountId, Int>
    fun save(counts: Map<AccountId, Int>)
}

internal data class MissedCallsRefreshId(val accountId: AccountId, val generation: Long)
internal data class MissedCallsUpdate(val applied: Boolean, val count: Int, val revision: Long)

internal data class MissedCallTarget(
    val occurredAt: Long,
    val accountId: AccountId,
    val login: String,
    val name: String?,
    val redialBinding: CallSessionBinding?,
    val missedCount: Int?,
    val callId: String? = null,
    val serverUrl: String? = redialBinding?.serverUrl,
)

internal data class MissedCallsSnapshot(
    val counts: Map<AccountId, Int>,
    val targets: List<MissedCallTarget>,
    val reconcileAccounts: Set<AccountId>,
) {
    val totalCount: Int get() = counts.values.sum()
}

/** Process-scoped source of missed-call state, independent of Android notification permissions/rendering. */
internal class MissedCallsRepository(
    private val persistence: MissedCallsPersistence,
    execute: (() -> Unit) -> Unit,
    private val publish: (MissedCallsSnapshot) -> Unit = {},
) {
    private val state = MissedCallsState(MissedCallsCounter()) { task ->
        execute { runCatching { task() } }
    }

    /** Persist only compact counts, never the whole history or contact list. */
    fun syncAccounts(accounts: Collection<AccountId>) {
        state.syncPersisted(accounts, persistence::load, persistence::save, ::publishSnapshot)
    }

    fun beginRefresh(accountId: AccountId): MissedCallsRefreshId? = state.beginRefresh(accountId)

    fun update(
        accountId: AccountId,
        unread: CallUnreadState,
        refreshId: MissedCallsRefreshId?,
        latest: MissedCallTarget? = null,
        redialBinding: CallSessionBinding? = latest?.redialBinding,
        immediate: Boolean = false,
    ): MissedCallsUpdate {
        val targets = missedCallTargets(accountId, unread, latest, redialBinding)
        return if (immediate) {
            state.updateImmediately(
                accountId, refreshId, unread.unreadMissedCount, persistence::save, ::publishSnapshot,
                newTargets = targets,
                authoritativeTargets = latest == null || unread.unreadMissed.isNotEmpty(),
            )
        } else {
            state.update(
                accountId, refreshId, unread.unreadMissedCount, persistence::save, ::publishSnapshot,
                newTargets = targets,
                authoritativeTargets = true,
            )
        }
    }

    fun recordMissedIfAbsent(accountId: AccountId, latest: MissedCallTarget?) {
        val refresh = beginRefresh(accountId)
        val count = state.snapshot()[accountId] ?: 0
        val unread = CallUnreadState(count.coerceAtLeast(1), emptyList())
        state.updateImmediately(
            accountId, refresh, unread.unreadMissedCount, persistence::save, ::publishSnapshot,
            newTargets = missedCallTargets(accountId, unread, latest, latest?.redialBinding),
            // A push is provisional even when older servers omit the caller's login.
            authoritativeTargets = false,
        )
    }

    fun removeAccount(accountId: AccountId) = state.remove(accountId, persistence::save, ::publishSnapshot)
    fun observeCount(observer: (Int) -> Unit) = state.observe(observer)
    fun removeCountObserver(observer: (Int) -> Unit) = state.removeObserver(observer)
    fun snapshot(): MissedCallsSnapshot = state.snapshotState()

    /** Keeps delayed consumers (e.g. an avatar load) from reviving an already-read call. */
    fun withCurrentTarget(
        count: Int,
        accountId: AccountId,
        login: String,
        matches: (MissedCallTarget) -> Boolean,
        action: (MissedCallTarget) -> Unit,
    ): Boolean = state.withCurrentTarget(count, accountId, login, matches, action)

    private fun publishSnapshot(@Suppress("UNUSED_PARAMETER") count: Int) {
        val snapshot = state.snapshotState()
        publish(snapshot)
        state.markReconciled(snapshot.reconcileAccounts)
    }
}

private fun missedCallTargets(
    accountId: AccountId,
    unread: CallUnreadState,
    latest: MissedCallTarget?,
    redialBinding: CallSessionBinding?,
): List<MissedCallTarget> {
    val provisional = latest?.takeIf { it.accountId == accountId && it.login.isNotBlank() }
    val historyByLogin = unread.unreadMissed
        .filter { it.peerLogin.isNotBlank() }
        .groupBy(UnreadMissedContact::peerLogin)
    val historyTargets = historyByLogin
        .mapNotNull { (_, entries) -> entries.maxByOrNull(UnreadMissedContact::startedAt) }
        .map { entry ->
            val matching = provisional?.takeIf { it.login == entry.peerLogin }
            val knownCount = entry.missedCount?.takeIf { it > 0 } ?: when {
                historyByLogin.size == 1 -> unread.unreadMissedCount.coerceAtLeast(1)
                unread.unreadMissedCount == historyByLogin.size -> 1
                else -> null
            }
            MissedCallTarget(
                occurredAt = entry.startedAt,
                accountId = accountId,
                login = entry.peerLogin,
                name = entry.peerName?.takeIf(String::isNotBlank) ?: matching?.name,
                redialBinding = redialBinding,
                missedCount = knownCount,
                callId = matching?.callId,
                serverUrl = redialBinding?.serverUrl?.takeIf(String::isNotBlank) ?: matching?.serverUrl,
            )
        }
    if (historyTargets.isNotEmpty()) return historyTargets
    return listOfNotNull(provisional?.copy(
        redialBinding = redialBinding,
        serverUrl = redialBinding?.serverUrl?.takeIf(String::isNotBlank) ?: provisional.serverUrl,
        missedCount = 1,
    ))
}

internal fun acknowledgeLatestMissedCall(
    login: String,
    loadLatestId: (String) -> Long?,
    markRead: (String, Long) -> CallUnreadState?,
): CallUnreadState? {
    val latestId = loadLatestId(login)?.takeIf { it > 0L } ?: return null
    return markRead(login, latestId)
}
