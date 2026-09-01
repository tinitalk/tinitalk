package org.tinitalk.call

import org.tinitalk.data.AccountId
import org.tinitalk.data.AuthStore
import org.tinitalk.data.Session
import org.tinitalk.data.normalizeServerUrl

data class AccountCallKey(
    val accountId: AccountId,
    val callId: String,
) {
    init {
        require(callId.isNotBlank()) { "call ID must not be blank" }
    }

    internal fun localId(): String = "${accountId.value.length}:${accountId.value}$callId"
}

data class CallSessionBinding(
    val serverUrl: String,
    val login: String,
    val sessionId: String?,
    val configId: String?,
) {
    fun matches(session: Session): Boolean =
        normalizeServerUrl(serverUrl) == normalizeServerUrl(session.url) &&
            login == session.login &&
            sessionId == session.sessionId &&
            configId == session.configId

    companion object {
        fun from(session: Session): CallSessionBinding = CallSessionBinding(
            serverUrl = normalizeServerUrl(session.url),
            login = session.login,
            sessionId = session.sessionId,
            configId = session.configId,
        )
    }
}

data class AccountCallOwner(
    val key: AccountCallKey,
    val sessionBinding: CallSessionBinding,
) {
    init {
        require(key.accountId.value.isNotBlank())
    }

    fun matchesRemoval(accountId: AccountId, binding: CallSessionBinding): Boolean =
        key.accountId == accountId && sessionBinding == binding

    internal fun localId(): String = buildString {
        appendField(key.localId())
        appendField(normalizeServerUrl(sessionBinding.serverUrl))
        appendField(sessionBinding.login)
        appendField(sessionBinding.sessionId)
        appendField(sessionBinding.configId)
    }

    private fun StringBuilder.appendField(value: String?) {
        if (value == null) {
            append("-1:")
        } else {
            append(value.length).append(':').append(value)
        }
    }
}

internal data class CallAdmissionLease(
    val owner: AccountCallOwner,
    internal val generation: Long,
)

internal enum class CallAdmissionState { Reserved, Running }

internal data class CurrentCallAdmission(
    val owner: AccountCallOwner,
    val state: CallAdmissionState,
)

internal sealed interface CallAdmissionAttempt {
    data class Acquired(val lease: CallAdmissionLease) : CallAdmissionAttempt
    data class Existing(val lease: CallAdmissionLease, val state: CallAdmissionState) : CallAdmissionAttempt
    data class Busy(val owner: AccountCallOwner) : CallAdmissionAttempt
}

internal class CallAdmission {
    private var nextGeneration = 0L
    private var owner: CallAdmissionLease? = null
    private var state: CallAdmissionState? = null

    @Synchronized
    fun reserve(next: AccountCallOwner): CallAdmissionAttempt {
        val current = owner
        if (current == null) {
            val lease = CallAdmissionLease(next, ++nextGeneration)
            owner = lease
            state = CallAdmissionState.Reserved
            return CallAdmissionAttempt.Acquired(lease)
        }
        return if (current.owner == next) {
            CallAdmissionAttempt.Existing(current, requireNotNull(state))
        } else {
            CallAdmissionAttempt.Busy(current.owner)
        }
    }

    @Synchronized
    fun markRunning(lease: CallAdmissionLease): Boolean {
        if (owner != lease || state != CallAdmissionState.Reserved) return false
        state = CallAdmissionState.Running
        return true
    }

    @Synchronized
    fun rekey(lease: CallAdmissionLease, key: AccountCallKey): CallAdmissionLease? {
        if (owner != lease) return null
        val replacement = lease.copy(owner = lease.owner.copy(key = key))
        owner = replacement
        return replacement
    }

    @Synchronized
    fun release(lease: CallAdmissionLease): Boolean {
        if (owner != lease) return false
        owner = null
        state = null
        return true
    }

    @Synchronized
    fun current(): CurrentCallAdmission? = owner?.let { CurrentCallAdmission(it.owner, requireNotNull(state)) }

    @Synchronized
    fun owns(lease: CallAdmissionLease): Boolean = owner == lease
}

internal interface CallAdmissionGateway {
    fun stage(owner: AccountCallOwner): CallAdmissionAttempt
    fun take(owner: AccountCallOwner): CallAdmissionLease?
    fun releaseStaged(owner: AccountCallOwner): Boolean
    fun release(lease: CallAdmissionLease): Boolean
    fun rekey(lease: CallAdmissionLease, key: AccountCallKey): CallAdmissionLease?
    fun current(): CurrentCallAdmission?
    fun owns(lease: CallAdmissionLease): Boolean
}

internal class CallAdmissionHandoff(private val admission: CallAdmission) : CallAdmissionGateway {
    private var staged: CallAdmissionLease? = null

    @Synchronized
    override fun stage(owner: AccountCallOwner): CallAdmissionAttempt {
        val attempt = admission.reserve(owner)
        if (attempt is CallAdmissionAttempt.Acquired) staged = attempt.lease
        return attempt
    }

    @Synchronized
    override fun take(owner: AccountCallOwner): CallAdmissionLease? {
        val lease = staged?.takeIf { it.owner == owner } ?: return null
        if (!admission.markRunning(lease)) return null
        staged = null
        return lease
    }

    @Synchronized
    override fun releaseStaged(owner: AccountCallOwner): Boolean {
        val lease = staged?.takeIf { it.owner == owner } ?: return false
        staged = null
        return admission.release(lease)
    }

    @Synchronized
    override fun release(lease: CallAdmissionLease): Boolean {
        if (staged == lease) staged = null
        return admission.release(lease)
    }

    @Synchronized
    override fun rekey(lease: CallAdmissionLease, key: AccountCallKey): CallAdmissionLease? =
        admission.rekey(lease, key)

    @Synchronized
    override fun current(): CurrentCallAdmission? = admission.current()
    @Synchronized
    override fun owns(lease: CallAdmissionLease): Boolean = admission.owns(lease)
}

internal object GlobalCallAdmission : CallAdmissionGateway {
    private val handoff = CallAdmissionHandoff(CallAdmission())

    override fun stage(owner: AccountCallOwner): CallAdmissionAttempt = handoff.stage(owner)
    override fun take(owner: AccountCallOwner): CallAdmissionLease? = handoff.take(owner)
    override fun releaseStaged(owner: AccountCallOwner): Boolean = handoff.releaseStaged(owner)
    override fun release(lease: CallAdmissionLease): Boolean = handoff.release(lease)
    override fun rekey(lease: CallAdmissionLease, key: AccountCallKey): CallAdmissionLease? = handoff.rekey(lease, key)
    override fun current(): CurrentCallAdmission? = handoff.current()
    override fun owns(lease: CallAdmissionLease): Boolean = handoff.owns(lease)
}

internal fun resolvePinnedCallSession(
    authStore: AuthStore,
    accountId: AccountId,
    binding: CallSessionBinding,
): Session? = authStore.get(accountId)?.session?.takeIf(binding::matches)
