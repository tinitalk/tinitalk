package org.tinitalk.call

import com.google.gson.JsonObject
import java.util.ArrayDeque
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallSASHandshakeTest {
    @Test
    fun hidesCodeUntilTransportReconnectsAndNeverRestoresItAfterFailure() {
        val exchange = Exchange()
        exchange.complete()
        val ready = exchange.callerStates.last()
        exchange.caller.onTransportUnavailable()
        assertEquals(CallSecurityState.Establishing, exchange.callerStates.last())
        exchange.caller.onTransportConnected()
        assertEquals(ready, exchange.callerStates.last())
        exchange.caller.reject(CallSecurityFailureReason.TransportFailed)
        exchange.caller.onTransportConnected()
        assertEquals(CallSecurityState.Failed(CallSecurityFailureReason.TransportFailed), exchange.callerStates.last())
    }

    @Test
    fun exchangesCommitKeyRevealAndPublishesCodeOnlyAfterTransportConnects() {
        val exchange = Exchange()

        exchange.start()

        assertEquals(listOf("rtc.sas.commit"), exchange.fromCaller.map { it.first })
        exchange.deliverCallerEvent()
        assertEquals(listOf("rtc.sas.key"), exchange.fromCallee.map { it.first })
        exchange.caller.recordRemoteSdp(exchange.answer)
        exchange.deliverCalleeEvent()
        assertEquals(listOf("rtc.sas.reveal"), exchange.fromCaller.map { it.first })
        exchange.deliverCallerEvent()
        assertFalse(exchange.callerStates.any { it is CallSecurityState.Ready })
        assertFalse(exchange.calleeStates.any { it is CallSecurityState.Ready })

        exchange.caller.onTransportConnected()
        exchange.callee.onTransportConnected()

        val callerCode = (exchange.callerStates.last() as CallSecurityState.Ready).code
        val calleeCode = (exchange.calleeStates.last() as CallSecurityState.Ready).code
        assertEquals(callerCode, calleeCode)
    }

    @Test
    fun changedRevealFailsInsteadOfProducingCode() {
        val exchange = Exchange()
        exchange.start()
        exchange.deliverCallerEvent()
        exchange.caller.recordRemoteSdp(exchange.answer)
        exchange.deliverCalleeEvent()
        val (_, reveal) = exchange.fromCaller.removeFirst()
        val changed = CallSecurityCode.decode(reveal["public_key"].asString).apply { this[0] = (this[0] + 1).toByte() }
        reveal.addProperty("public_key", CallSecurityCode.encode(changed))

        exchange.callee.onReveal(reveal)
        exchange.callee.onTransportConnected()

        assertEquals(
            CallSecurityState.Failed(CallSecurityFailureReason.CommitmentMismatch),
            exchange.calleeStates.last(),
        )
        assertFalse(exchange.calleeStates.any { it is CallSecurityState.Ready })
    }

    @Test
    fun changedFingerprintDuringRenegotiationInvalidatesCode() {
        val exchange = Exchange()
        exchange.complete()

        exchange.caller.recordLocalSdp(sdp(7))

        assertEquals(
            CallSecurityState.Failed(CallSecurityFailureReason.FingerprintChanged),
            exchange.callerStates.last(),
        )
    }

    @Test
    fun incompleteExchangeFailsOnTimeout() {
        val states = mutableListOf<CallSecurityState>()
        val handshake = CallSASHandshake(
            CallId,
            "alice",
            "bob",
            CallSASRole.Caller,
            { _, _ -> },
            states::add,
        )

        handshake.timeOut()

        assertEquals(CallSecurityState.Failed(CallSecurityFailureReason.ExchangeTimeout), states.last())
    }

    @Test
    fun completedExchangeFailsIfWebRtcTransportDoesNotConnectInTime() {
        val exchange = Exchange()
        exchange.start()
        exchange.deliverCallerEvent()
        exchange.caller.recordRemoteSdp(exchange.answer)
        exchange.deliverCalleeEvent()
        exchange.deliverCallerEvent()

        exchange.caller.timeOut()

        assertEquals(
            CallSecurityState.Failed(CallSecurityFailureReason.TransportTimeout),
            exchange.callerStates.last(),
        )
    }

    private class Exchange {
        val offer = sdp(0)
        val answer = sdp(32)
        val fromCaller = ArrayDeque<Pair<String, JsonObject>>()
        val fromCallee = ArrayDeque<Pair<String, JsonObject>>()
        val callerStates = mutableListOf<CallSecurityState>()
        val calleeStates = mutableListOf<CallSecurityState>()
        val caller = CallSASHandshake(
            CallId,
            "alice",
            "bob",
            CallSASRole.Caller,
            { type, payload -> fromCaller += type to payload },
            callerStates::add,
        )
        val callee = CallSASHandshake(
            CallId,
            "alice",
            "bob",
            CallSASRole.Callee,
            { type, payload -> fromCallee += type to payload },
            calleeStates::add,
        )

        fun start() {
            caller.recordLocalSdp(offer)
            callee.recordRemoteSdp(offer)
            callee.recordLocalSdp(answer)
        }

        fun complete() {
            start()
            deliverCallerEvent()
            caller.recordRemoteSdp(answer)
            deliverCalleeEvent()
            deliverCallerEvent()
            caller.onTransportConnected()
            callee.onTransportConnected()
        }

        fun deliverCallerEvent() {
            val (type, payload) = fromCaller.removeFirst()
            when (type) {
                "rtc.sas.commit" -> callee.onCommitment(payload)
                "rtc.sas.reveal" -> callee.onReveal(payload)
                else -> error("unexpected caller event $type")
            }
        }

        fun deliverCalleeEvent() {
            val (type, payload) = fromCallee.removeFirst()
            check(type == "rtc.sas.key")
            caller.onKey(payload)
        }
    }

    companion object {
        private const val CallId = "018f7d51-40a1-7bb5-a2d0-7e47f9181766"

        private fun sdp(offset: Int): String =
            "v=0\r\na=fingerprint:sha-256 " +
                ByteArray(32) { (it + offset).toByte() }
                    .joinToString(":") { "%02X".format(it.toInt() and 0xff) } +
                "\r\n"
    }
}
