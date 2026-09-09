package org.tinitalk.call

import com.google.gson.JsonObject
import org.tinitalk.data.AccountId
import org.tinitalk.data.signal.SignalEvent
import java.util.UUID

data class SequencedSignalEvent(val event: SignalEvent, val seq: Long)

interface SignalClient {
    fun send(event: SignalEvent, onSettled: (() -> Unit)? = null)

    fun sendTracked(event: SignalEvent, onResult: (SignalSendResult) -> Unit) {
        send(event) { onResult(SignalSendResult.Unconfirmed) }
    }
}

enum class SignalSendResult {
    Acknowledged,
    Rejected,
    Unconfirmed,
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
    accountId: AccountId = AccountId("single-account"),
) {
    private val machine = CallStateMachine(accountId)
    private var connectedCallId: String? = null

    fun snapshot(): CallSnapshot = machine.snapshot()

    fun startCall(callee: String, callId: String = ids.nextCallId()) {
        require(callee != self) { "cannot call self" }
        val payload = JsonObject().apply {
            addProperty("callee_id", callee)
            addProperty("supports_cross_call", true)
            addProperty("supports_video", true)
            addProperty("supports_exclusive_screen_sharing", true)
            addProperty("supports_call_sas", true)
        }
        signal.send(event(callId, "call.start", payload))
        machine.transition(CallPhase.Connecting, callId)
    }

    fun accept() {
        val callId = requireNotNull(machine.snapshot().callId) { "no call" }
        val payload = JsonObject().apply {
            addProperty("supports_video", true)
            addProperty("supports_exclusive_screen_sharing", true)
            addProperty("supports_call_sas", true)
        }
        signal.send(event(callId, "call.accept", payload))
        machine.transition(CallPhase.Active, callId)
    }

    fun reject(onSettled: (() -> Unit)? = null) {
        sendTerminal("call.reject", onSettled)
    }

    fun reject(eventId: String, onSettled: (() -> Unit)? = null) {
        sendTerminal("call.reject", onSettled, eventId)
    }

    fun reject(
        replyCode: CallReplyCode,
        eventId: String? = null,
        onResult: (SignalSendResult) -> Unit,
    ) {
        val payload = JsonObject().apply { addProperty("reply_code", replyCode.wireValue) }
        sendTrackedTerminal("call.reject", payload, eventId, onResult)
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
        val before = machine.snapshot()
        if (incoming.seq <= before.lastSeq) return false
        val adoptsCrossedCall = incoming.event.type == "call.accept" &&
            (before.phase == CallPhase.Connecting || before.phase == CallPhase.Ringing) &&
            incoming.event.payload["crossed"]?.asBoolean == true
        if (before.callId != null && incoming.event.callId != before.callId && !adoptsCrossedCall) return false
        if (before.phase == CallPhase.Ended) {
            if (incoming.event.type !in TerminalEventTypes || incoming.event.callId != before.callId) return false
            machine.recordSeq(incoming.seq)
            return true
        }
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

    private fun sendTerminal(type: String, onSettled: (() -> Unit)?, eventId: String? = null) {
        val callId = requireNotNull(machine.snapshot().callId) { "no call" }
        signal.send(event(callId, type, JsonObject(), eventId), onSettled)
        machine.transition(CallPhase.Ended, callId)
    }

    private fun sendTrackedTerminal(
        type: String,
        payload: JsonObject,
        eventId: String?,
        onResult: (SignalSendResult) -> Unit,
    ) {
        val callId = requireNotNull(machine.snapshot().callId) { "no call" }
        signal.sendTracked(event(callId, type, payload, eventId), onResult)
        machine.transition(CallPhase.Ended, callId)
    }

    private fun event(
        callId: String,
        type: String,
        payload: JsonObject,
        eventId: String? = null,
    ): SignalEvent = SignalEvent(eventId ?: ids.nextEventId(), callId, type, ids.nowMillis(), payload)

    private companion object {
        val TerminalEventTypes = setOf("call.reject", "call.cancel", "call.end", "call.expire")
    }
}
