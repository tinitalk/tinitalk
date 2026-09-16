package org.tinitalk.missed

import org.tinitalk.data.AccountId
import java.util.concurrent.CopyOnWriteArraySet

/** Per-account generation gates prevent an old server response from replacing another server's badge. */
internal class MissedCallsCounter(initial: Map<AccountId, Int> = emptyMap()) {
    private val counts = initial.mapValues { it.value.coerceAtLeast(0) }.toMutableMap()
    private val generations = initial.keys.associateWith { 0L }.toMutableMap()
    private var revision = 0L

    @Synchronized fun sync(active: Collection<AccountId>, persisted: Map<AccountId, Int> = emptyMap()): Int {
        val allowed = active.toSet()
        counts.keys.retainAll(allowed)
        generations.keys.retainAll(allowed)
        active.forEach { id ->
            counts.putIfAbsent(id, persisted[id]?.coerceAtLeast(0) ?: 0)
            generations.putIfAbsent(id, 0L)
        }
        revision++
        return counts.values.sum()
    }

    @Synchronized fun beginRefresh(accountId: AccountId): MissedCallsRefreshId? {
        val previous = generations[accountId] ?: return null
        return MissedCallsRefreshId(accountId, previous + 1L).also { generations[accountId] = it.generation }
    }

    @Synchronized fun update(accountId: AccountId, refreshId: MissedCallsRefreshId?, count: Int): MissedCallsUpdate {
        if (refreshId?.accountId != accountId || generations[accountId] != refreshId.generation || accountId !in counts) {
            return MissedCallsUpdate(false, counts.values.sum(), revision)
        }
        counts[accountId] = count.coerceAtLeast(0)
        revision++
        return MissedCallsUpdate(true, counts.values.sum(), revision)
    }

    @Synchronized fun remove(accountId: AccountId): MissedCallsUpdate {
        counts.remove(accountId)
        generations.remove(accountId)
        revision++
        return MissedCallsUpdate(true, counts.values.sum(), revision)
    }

    @Synchronized fun snapshot(): Map<AccountId, Int> = counts.toMap()
    @Synchronized fun isCurrentRevision(value: Long): Boolean = revision == value
    @Synchronized fun currentUpdate(): MissedCallsUpdate =
        MissedCallsUpdate(true, counts.values.sum(), revision)
}

internal class MissedCallsState(
    private val counter: MissedCallsCounter,
    private val execute: ((() -> Unit) -> Unit),
) {
    private val observers = CopyOnWriteArraySet<(Int) -> Unit>()
    private val targets = mutableMapOf<AccountId, MutableMap<String, MissedCallTarget>>()
    private val pendingReconcileAccounts = linkedSetOf<AccountId>()

    fun sync(active: Collection<AccountId>, persisted: Map<AccountId, Int>): Int = synchronized(this) {
        markInactiveAccountsForReconcile(active, persisted.keys)
        retainActiveTargets(active)
        counter.sync(active, persisted).also(::notifyObservers)
    }
    fun syncPersisted(
        active: Collection<AccountId>,
        load: () -> Map<AccountId, Int>,
        save: (Map<AccountId, Int>) -> Unit,
        publish: ((Int) -> Unit)? = null,
    ): Int = synchronized(this) {
        val persisted = load()
        markInactiveAccountsForReconcile(active, persisted.keys)
        retainActiveTargets(active)
        counter.sync(active, persisted).also { count ->
            save(counter.snapshot())
            notifyObservers(count)
            publish?.let { callback ->
                val update = counter.currentUpdate()
                execute { publishIfCurrent(update, callback) }
            }
        }
    }
    fun beginRefresh(accountId: AccountId): MissedCallsRefreshId? = counter.beginRefresh(accountId)
    fun observe(observer: (Int) -> Unit) { observers += observer; observer(counter.snapshot().values.sum()) }
    fun removeObserver(observer: (Int) -> Unit) { observers -= observer }
    fun remove(accountId: AccountId, persist: (Map<AccountId, Int>) -> Unit, publish: (Int) -> Unit) = synchronized(this) {
        pendingReconcileAccounts += accountId
        targets.remove(accountId)
        counter.remove(accountId).also { update -> persist(counter.snapshot()); notifyObservers(update.count); execute { publishIfCurrent(update, publish) } }
    }
    fun update(
        accountId: AccountId,
        refreshId: MissedCallsRefreshId?,
        count: Int,
        persist: (Map<AccountId, Int>) -> Unit,
        publish: (Int) -> Unit,
        newTargets: List<MissedCallTarget> = emptyList(),
        authoritativeTargets: Boolean = false,
    ): MissedCallsUpdate = synchronized(this) {
        counter.update(accountId, refreshId, count).also { update ->
            if (update.applied) {
                pendingReconcileAccounts += accountId
                updateTargets(accountId, count, newTargets, authoritativeTargets)
                persist(counter.snapshot())
                notifyObservers(update.count)
                execute { publishIfCurrent(update, publish) }
            }
        }
    }
    fun updateImmediately(
        accountId: AccountId,
        refreshId: MissedCallsRefreshId?,
        count: Int,
        persist: (Map<AccountId, Int>) -> Unit,
        publish: (Int) -> Unit,
        newTargets: List<MissedCallTarget> = emptyList(),
        authoritativeTargets: Boolean = false,
    ): MissedCallsUpdate = synchronized(this) {
        counter.update(accountId, refreshId, count).also { update ->
            if (update.applied) {
                pendingReconcileAccounts += accountId
                updateTargets(accountId, count, newTargets, authoritativeTargets)
                persist(counter.snapshot())
                notifyObservers(update.count)
                publish(update.count)
            }
        }
    }
    private fun updateTargets(
        accountId: AccountId,
        count: Int,
        newTargets: List<MissedCallTarget>,
        authoritativeTargets: Boolean,
    ) {
        if (count <= 0) {
            targets.remove(accountId)
            return
        }
        if (authoritativeTargets) {
            targets[accountId] = newTargets
                .filter { it.accountId == accountId }
                .associateByTo(linkedMapOf(), MissedCallTarget::login)
            return
        }
        if (newTargets.isEmpty()) return
        val accountTargets = targets.getOrPut(accountId, ::linkedMapOf)
        newTargets.filter { it.accountId == accountId }.forEach { candidate ->
            val current = accountTargets[candidate.login]
            if (current == null || candidate.occurredAt >= current.occurredAt) {
                accountTargets[candidate.login] = candidate.copy(
                    missedCount = listOfNotNull(candidate.missedCount, current?.missedCount).maxOrNull(),
                )
            }
        }
    }
    private fun markInactiveAccountsForReconcile(
        active: Collection<AccountId>,
        persistedAccounts: Collection<AccountId>,
    ) {
        val allowed = active.toSet()
        pendingReconcileAccounts += (targets.keys + counter.snapshot().keys + persistedAccounts) - allowed
    }
    private fun retainActiveTargets(active: Collection<AccountId>) = targets.keys.retainAll(active.toSet())
    fun targetsSnapshot(): List<MissedCallTarget> = synchronized(this) {
        targets.values.flatMap { it.values }.toList()
    }
    fun withCurrentTarget(
        count: Int,
        accountId: AccountId,
        login: String,
        matches: (MissedCallTarget) -> Boolean,
        publish: (MissedCallTarget) -> Unit,
    ): Boolean = synchronized(this) {
        if (counter.snapshot().values.sum() != count) return@synchronized false
        val target = targets[accountId]?.get(login) ?: return@synchronized false
        if (!matches(target)) return@synchronized false
        publish(target)
        true
    }
    fun pendingReconcileAccounts(): Set<AccountId> = synchronized(this) {
        pendingReconcileAccounts.toSet()
    }
    fun markReconciled(accounts: Set<AccountId>) = synchronized(this) {
        pendingReconcileAccounts.removeAll(accounts)
    }
    private fun publishIfCurrent(update: MissedCallsUpdate, publish: (Int) -> Unit) = synchronized(this) {
        if (counter.isCurrentRevision(update.revision)) publish(update.count)
    }
    private fun notifyObservers(count: Int) = observers.forEach { observer -> runCatching { observer(count) } }
    fun snapshot(): Map<AccountId, Int> = counter.snapshot()
    fun snapshotState(): MissedCallsSnapshot = synchronized(this) {
        MissedCallsSnapshot(counter.snapshot(), targetsSnapshot(), pendingReconcileAccounts.toSet())
    }
}
