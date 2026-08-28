package org.tinitalk.call

import com.google.gson.JsonObject
import org.tinitalk.data.signal.SignalEvent
import java.util.UUID

data class SequencedSignalEvent(val event: SignalEvent, val seq: Long)

interface SignalClient {
    fun send(event: SignalEvent, onSettled: (() -> Unit)? = null)
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
    private var connectedCallId: String? = null

    fun snapshot(): CallSnapshot = machine.snapshot()

    fun startCall(callee: String) {
        require(callee != self) { "cannot call self" }
        val callId = ids.nextCallId()
        val payload = JsonObject().apply {
            addProperty("callee_id", callee)
            addProperty("supports_cross_call", true)
        }
        signal.send(event(callId, "call.start", payload))
        machine.transition(CallPhase.Connecting, callId)
    }

    fun accept() {
        val callId = requireNotNull(machine.snapshot().callId) { "no call" }
        signal.send(event(callId, "call.accept", JsonObject()))
        machine.transition(CallPhase.Active, callId)
    }

    fun reject(onSettled: (() -> Unit)? = null) {
        sendTerminal("call.reject", onSettled)
    }

    fun cancel(onSettled: (() -> Unit)? = null) {
        sendTerminal("call.cancel", onSettled)
    }

    fun hangUp(onSettled: (() -> Unit)? = null) {
        sendTerminal("call.end", onSettled)
    }

    fun mediaConnected() {
        val current = machine.snapshot()
        val callId = current.callId ?: return
        if (current.phase != CallPhase.Active || connectedCallId == callId) return
        signal.send(event(callId, "call.connected", JsonObject()))
        connectedCallId = callId
    }

    fun finish() {
        machine.reset()
        connectedCallId = null
    }

    fun fail() {
        val current = machine.snapshot()
        if (current.phase != CallPhase.Ended) {
            machine.transition(CallPhase.Ended, current.callId)
        }
    }

    fun resume() {
        val callId = machine.snapshot().callId ?: return
        val payload = JsonObject().apply { addProperty("last_seq", machine.snapshot().lastSeq) }
        signal.send(event(callId, "call.resume", payload))
    }

    fun restoreIncoming(
        callId: String,
        lastSeq: Long = 0,
        acknowledgeRinging: Boolean = true,
        onRingingSettled: (() -> Unit)? = null,
    ) {
        if (machine.snapshot().phase == CallPhase.Idle) {
            machine.transition(CallPhase.Ringing, callId)
        }
        machine.recordSeq(lastSeq)
        if (acknowledgeRinging && machine.snapshot().phase == CallPhase.Ringing) {
            signal.send(event(callId, "call.ringing", JsonObject()), onRingingSettled)
        }
    }

    fun onEvent(incoming: SequencedSignalEvent): Boolean {
        if (incoming.seq <= machine.snapshot().lastSeq) return false
        machine.recordSeq(incoming.seq)
        when (incoming.event.type) {
            "call.incoming" -> machine.transition(CallPhase.Ringing, incoming.event.callId)
            "call.ringing" -> if (machine.snapshot().phase == CallPhase.Connecting) {
                machine.transition(CallPhase.Ringing, incoming.event.callId)
            }
            "call.accept" -> machine.transition(CallPhase.Active, incoming.event.callId)
            "call.reject", "call.cancel", "call.end", "call.expire" -> machine.transition(CallPhase.Ended, incoming.event.callId)
        }
        return true
    }

    private fun sendTerminal(type: String, onSettled: (() -> Unit)?) {
        val callId = requireNotNull(machine.snapshot().callId) { "no call" }
        signal.send(event(callId, type, JsonObject()), onSettled)
        machine.transition(CallPhase.Ended, callId)
    }

    private fun event(callId: String, type: String, payload: JsonObject): SignalEvent =
        SignalEvent(ids.nextEventId(), callId, type, ids.nowMillis(), payload)
}
