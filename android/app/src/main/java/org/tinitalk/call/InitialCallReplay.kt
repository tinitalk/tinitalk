package org.tinitalk.call

import org.tinitalk.data.signal.SignalEvent

/** Main-thread barrier between live delivery and the initial replay of an accepted waiting call. */
internal class InitialCallReplay(val callId: String, val lastSeq: Long) {
    private val events = sortedMapOf<Long, SequencedSignalEvent>()
    var requested = false
        private set

    fun request(): Boolean {
        if (requested) return false
        requested = true
        return true
    }

    fun add(event: SequencedSignalEvent) {
        if (event.event.callId != callId || event.seq <= lastSeq) return
        check(event.seq in events || events.size < SignalEvent.EVENT_BUFFER_LIMIT) {
            "Initial call replay buffer overflow"
        }
        events.putIfAbsent(event.seq, event)
    }

    fun drain(): List<SequencedSignalEvent> = events.values.toList().also { events.clear() }
}
