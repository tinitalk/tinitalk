package org.tinitalk.call

import com.google.gson.JsonObject
import org.tinitalk.data.signal.SignalEvent
import java.util.UUID

data class SequencedSignalEvent(val event: SignalEvent, val seq: Long)

interface SignalClient {
    fun send(event: SignalEvent)
}

interface EventIds {
    fun nextEventId(): String
    fun nextCallId(): String
    fun nowMillis(): Long
}

class UuidEventIds : EventIds {
    override fun nextEventId(): String = UUID.randomUUID().toString()
    override fun nextCallId(): String = UUID.randomUUID().toString()
    override fun nowMillis(): Long = System.currentTimeMillis()
}

class CallCoordinator(
    private val self: String,
    private val signal: SignalClient,
    private val ids: EventIds = UuidEventIds(),
) {
    private val machine = CallStateMachine()

    fun snapshot(): CallSnapshot = machine.snapshot()

    fun startCall(callee: String) {
        require(callee != self) { "cannot call self" }
        val callId = ids.nextCallId()
        val payload = JsonObject().apply { addProperty("callee_id", callee) }
        signal.send(event(callId, "call.start", payload))
        machine.transition(CallPhase.Connecting, callId)
    }

    fun accept() {
        val callId = requireNotNull(machine.snapshot().callId) { "no call" }
        signal.send(event(callId, "call.accept", JsonObject()))
        machine.transition(CallPhase.Connecting, callId)
    }

    fun reject() {
        sendTerminal("call.reject")
    }

    fun cancel() {
        sendTerminal("call.cancel")
    }

    fun resume() {
        val callId = machine.snapshot().callId ?: return
        val payload = JsonObject().apply { addProperty("last_seq", machine.snapshot().lastSeq) }
        signal.send(event(callId, "call.resume", payload))
    }

    fun onEvent(incoming: SequencedSignalEvent) {
        if (incoming.seq <= machine.snapshot().lastSeq) return
        machine.recordSeq(incoming.seq)
        when (incoming.event.type) {
            "call.incoming" -> machine.transition(CallPhase.Ringing, incoming.event.callId)
            "call.ringing" -> if (machine.snapshot().phase == CallPhase.Connecting) Unit else machine.transition(CallPhase.Ringing, incoming.event.callId)
            "call.accept" -> machine.transition(CallPhase.Active, incoming.event.callId)
            "call.reject", "call.cancel", "call.end", "call.expire" -> machine.transition(CallPhase.Ended, incoming.event.callId)
        }
    }

    private fun sendTerminal(type: String) {
        val callId = requireNotNull(machine.snapshot().callId) { "no call" }
        signal.send(event(callId, type, JsonObject()))
        machine.transition(CallPhase.Ended, callId)
    }

    private fun event(callId: String, type: String, payload: JsonObject): SignalEvent =
        SignalEvent(ids.nextEventId(), callId, type, ids.nowMillis(), payload)
}
