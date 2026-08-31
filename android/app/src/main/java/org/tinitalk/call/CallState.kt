package org.tinitalk.call

import org.tinitalk.data.AccountId

enum class CallPhase {
    Idle,
    Ringing,
    Connecting,
    Active,
    Ended,
}

data class CallSnapshot(
    val phase: CallPhase = CallPhase.Idle,
    val callId: String? = null,
    val lastSeq: Long = 0,
    val accountId: AccountId? = null,
) {
    val callKey: AccountCallKey? get() = callId?.let { id -> accountId?.let { AccountCallKey(it, id) } }
}

class CallStateMachine(private val accountId: AccountId = AccountId("single-account")) {
    private var snapshot = CallSnapshot(accountId = accountId)

    fun snapshot(): CallSnapshot = snapshot

    fun transition(next: CallPhase, callId: String? = snapshot.callId) {
        val current = snapshot.phase
        require(allowed(current, next)) { "invalid call transition $current -> $next" }
        snapshot = snapshot.copy(phase = next, callId = callId)
    }

    fun recordSeq(seq: Long) {
        if (seq > snapshot.lastSeq) {
            snapshot = snapshot.copy(lastSeq = seq)
        }
    }

    fun reset() {
        require(snapshot.phase == CallPhase.Ended) { "only ended call can be reset" }
        snapshot = CallSnapshot(accountId = accountId)
    }

    private fun allowed(current: CallPhase, next: CallPhase): Boolean =
        when (current) {
            CallPhase.Idle -> next == CallPhase.Ringing || next == CallPhase.Connecting || next == CallPhase.Ended
            CallPhase.Ringing -> next == CallPhase.Connecting || next == CallPhase.Active || next == CallPhase.Ended
            CallPhase.Connecting -> next == CallPhase.Ringing || next == CallPhase.Active || next == CallPhase.Ended
            CallPhase.Active -> next == CallPhase.Ended
            CallPhase.Ended -> false
        }
}
