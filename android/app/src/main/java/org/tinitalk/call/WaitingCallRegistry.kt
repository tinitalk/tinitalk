package org.tinitalk.call

import org.tinitalk.push.IncomingInvite

/** Pending signaling only: entries never own a microphone, camera or Telecom call. */
internal data class WaitingCall(
    val invite: IncomingInvite,
    val deadlineElapsedMs: Long,
    val originalDeadlineElapsedMs: Long,
    val lastSeq: Long = invite.lastSeq,
    val acknowledged: Boolean = false,
    val waiting: Boolean = true,
)

internal data class WaitingCallSelection(
    val owner: AccountCallOwner,
    val previous: AccountCallOwner?,
    val generation: Long,
)

/** All deadlines use elapsed time. Server acknowledgements may shorten, never extend them. */
internal class WaitingCallRegistry(private val limit: Int = 8) {
    private val calls = linkedMapOf<AccountCallOwner, WaitingCall>()
    private var generation = 0L
    private var selection: WaitingCallSelection? = null

    @Synchronized
    fun add(invite: IncomingInvite, nowElapsedMs: Long, remainingMillis: Long): Boolean {
        if (invite.owner in calls) return true
        if (remainingMillis <= 0 || calls.size >= limit || calls.keys.any { it.key == invite.key }) return false
        calls[invite.owner] = WaitingCall(
            invite = invite,
            deadlineElapsedMs = nowElapsedMs + minOf(15_000L, remainingMillis),
            originalDeadlineElapsedMs = nowElapsedMs + remainingMillis,
        )
        return true
    }

    @Synchronized
    fun acknowledge(
        owner: AccountCallOwner,
        seq: Long,
        remainingMillis: Long,
        nowElapsedMs: Long,
        waiting: Boolean = true,
    ): Boolean {
        val call = calls[owner] ?: return false
        if (seq <= call.lastSeq) return false
        // Only a confirmed promotion to ordinary ringing can restore the original
        // ringing deadline. Replays of a waiting acknowledgement never extend it.
        val upperBound = if (call.waiting && !waiting) {
            call.originalDeadlineElapsedMs
        } else call.deadlineElapsedMs
        calls[owner] = call.copy(
            lastSeq = seq,
            acknowledged = true,
            waiting = waiting,
            deadlineElapsedMs = minOf(upperBound, nowElapsedMs + remainingMillis.coerceAtLeast(0)),
        )
        return true
    }

    @Synchronized
    fun snapshot(): List<WaitingCall> = calls.values.toList()

    @Synchronized
    fun get(owner: AccountCallOwner): WaitingCall? = calls[owner]

    @Synchronized
    fun remove(owner: AccountCallOwner): WaitingCall? {
        if (selection?.owner == owner) selection = null
        return calls.remove(owner)
    }

    @Synchronized
    fun removeTerminal(owner: AccountCallOwner, seq: Long): WaitingCall? {
        val call = calls[owner] ?: return null
        if (seq <= call.lastSeq) return null
        return remove(owner)
    }

    @Synchronized
    fun select(owner: AccountCallOwner, previous: AccountCallOwner?, nowElapsedMs: Long): WaitingCallSelection? {
        if (selection != null) return null
        val call = calls[owner] ?: return null
        if (!call.acknowledged || call.deadlineElapsedMs <= nowElapsedMs) return null
        return WaitingCallSelection(owner, previous, ++generation).also { selection = it }
    }

    @Synchronized
    fun isSelected(expected: WaitingCallSelection): Boolean = selection == expected && expected.owner in calls

    @Synchronized
    fun clearSelection(expected: WaitingCallSelection) {
        if (selection == expected) selection = null
    }

    @Synchronized
    fun currentSelection(): WaitingCallSelection? = selection

    @Synchronized
    fun expired(nowElapsedMs: Long): List<WaitingCall> = calls.values.filter {
        it.deadlineElapsedMs <= nowElapsedMs && selection?.owner != it.invite.owner
    }
}
