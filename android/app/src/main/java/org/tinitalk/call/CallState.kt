package org.tinitalk.call

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
)

class CallStateMachine {
    private var snapshot = CallSnapshot()

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
        snapshot = CallSnapshot()
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
