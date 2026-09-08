package org.tinitalk.call

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.tinitalk.data.AccountId
import org.tinitalk.data.signal.SignalEvent
import org.tinitalk.media.CallStats
import org.tinitalk.media.IceCandidateData
import org.tinitalk.media.IceServerData
import org.tinitalk.media.MediaConnectionState
import org.tinitalk.media.MediaSession

class ForegroundCallSASTest {
    @Test
    fun foreignRtcConfigCannotReplaceTheBoundMediaSession() {
        val signal = CapturingSignalClient()
        val states = mutableListOf<CallSecurityState>()
        val controller = controller("alice", signal, sdp(0), sdp(32), states)
        controller.prepareSecurityCode(CallId, "bob", localIsCaller = true)
        controller.onSignalEvent(active(), event("call.accept"))
        controller.onSignalEvent(active(), config(sasAllowed = true))
        val foreign = "00000000-0000-0000-0000-000000000098"
        controller.onSignalEvent(active(), event("call.accept", JsonObject().apply { addProperty("crossed", true) }).copy(callId = foreign))
        controller.onSignalEvent(active(), config(sasAllowed = true).copy(callId = foreign))
        controller.onSignalEvent(active(), event("rtc.offer", JsonObject().apply { addProperty("sdp", sdp(7)) }).copy(callId = foreign))
        assertTrue(signal.sent.all { it.callId == CallId })
    }

    @Test
    fun classifiedSASTimeoutDoesNotCloseMedia() {
        val signal = CapturingSignalClient()
        val states = mutableListOf<CallSecurityState>()
        val controller = controller("alice", signal, sdp(0), sdp(32), states)
        controller.prepareSecurityCode(CallId, "bob", localIsCaller = true)
        controller.onSignalEvent(active(), event("call.accept"))
        controller.onSignalEvent(active(), config(sasAllowed = true))
        controller.onSignalFailure(org.tinitalk.data.signal.SignalFailure("timeout", "call_sas_timeout", CallId))
        assertEquals(CallSecurityState.Failed(CallSecurityFailureReason.ExchangeTimeout), states.last())
        controller.onSignalEvent(active(), event("rtc.offer", JsonObject().apply { addProperty("sdp", sdp(32)) }))
        assertTrue(signal.sent.any { it.type == "rtc.answer" })
        assertEquals(CallSecurityState.Failed(CallSecurityFailureReason.ExchangeTimeout), states.last())
    }

    @Test
    fun bindsHandshakeToOfferAndAnswerFingerprints() {
        val callerSignal = CapturingSignalClient()
        val calleeSignal = CapturingSignalClient()
        val callerStates = mutableListOf<CallSecurityState>()
        val calleeStates = mutableListOf<CallSecurityState>()
        val caller = controller("alice", callerSignal, sdp(0), sdp(99), callerStates)
        val callee = controller("bob", calleeSignal, sdp(99), sdp(32), calleeStates)
        caller.prepareSecurityCode(CallId, "bob", localIsCaller = true)
        callee.prepareSecurityCode(CallId, "alice", localIsCaller = false)

        caller.onSignalEvent(active(), event("call.accept"))
        caller.onSignalEvent(active(), config(sasAllowed = true))
        callee.onSignalEvent(active(), config(sasAllowed = true))
        assertEquals(listOf("rtc.sas.commit", "rtc.offer"), callerSignal.sent.map { it.type })

        callee.onSignalEvent(active(), callerSignal.take("rtc.sas.commit"))
        callee.onSignalEvent(active(), callerSignal.take("rtc.offer"))
        assertEquals(listOf("rtc.sas.key", "rtc.answer"), calleeSignal.sent.map { it.type })

        caller.onSignalEvent(active(), calleeSignal.take("rtc.sas.key"))
        caller.onSignalEvent(active(), calleeSignal.take("rtc.answer"))
        callee.onSignalEvent(active(), callerSignal.take("rtc.sas.reveal"))
        caller.onMediaConnection(CallId, 1, MediaConnectionState.Connected)
        callee.onMediaConnection(CallId, 1, MediaConnectionState.Connected)

        assertFalse(callerStates.last() is CallSecurityState.Ready)
        assertFalse(calleeStates.last() is CallSecurityState.Ready)

        caller.onTransportConnection(CallId, MediaConnectionState.Connected)
        callee.onTransportConnection(CallId, MediaConnectionState.Connected)
        val callerCode = (callerStates.last() as CallSecurityState.Ready).code
        val calleeCode = (calleeStates.last() as CallSecurityState.Ready).code
        assertEquals(callerCode, calleeCode)
        caller.onTransportConnection(CallId, MediaConnectionState.Disconnected)
        assertEquals(CallSecurityState.Establishing, callerStates.last())
        caller.onTransportConnection(CallId, MediaConnectionState.Connected)
        assertEquals(CallSecurityState.Ready(callerCode), callerStates.last())
        caller.onSignalEvent(active(), config(sasAllowed = false))
        assertTrue(callerStates.last() is CallSecurityState.Failed)
        caller.onSignalEvent(active(), config(sasAllowed = true))
        caller.onTransportConnection(CallId, MediaConnectionState.Connected)
        assertTrue(callerStates.last() is CallSecurityState.Failed)
    }

    @Test
    fun oldCallConfigDoesNotStartSAS() {
        val signal = CapturingSignalClient()
        val states = mutableListOf<CallSecurityState>()
        val caller = controller("alice", signal, sdp(0), sdp(32), states)
        caller.prepareSecurityCode(CallId, "bob", localIsCaller = true)

        caller.onSignalEvent(active(), event("call.accept"))
        caller.onSignalEvent(active(), config(sasAllowed = false))

        assertEquals(listOf("rtc.offer"), signal.sent.map { it.type })
        assertEquals(
            CallSecurityState.Unavailable(CallSecurityUnavailableReason.PeerUnsupported),
            states.last(),
        )
        assertFalse(signal.sent.any { it.type.startsWith("rtc.sas.") })
    }

    @Test
    fun configFromOldServerExplainsThatServerIsUnsupported() {
        val signal = CapturingSignalClient()
        val states = mutableListOf<CallSecurityState>()
        val caller = controller("alice", signal, sdp(0), sdp(32), states)
        caller.prepareSecurityCode(CallId, "bob", localIsCaller = true)

        caller.onSignalEvent(active(), event("call.accept"))
        caller.onSignalEvent(active(), event("rtc.config", JsonObject().apply {
            add("ice_servers", JsonArray())
        }))

        assertEquals(
            CallSecurityState.Unavailable(CallSecurityUnavailableReason.ServerUnsupported),
            states.last(),
        )
        assertFalse(signal.sent.any { it.type.startsWith("rtc.sas.") })
    }

    @Test
    fun crossedCallUsesCanonicalOffererAsCallerRole() {
        val signal = CapturingSignalClient()
        val states = mutableListOf<CallSecurityState>()
        val controller = controller("bob", signal, sdp(0), sdp(32), states)
        controller.prepareSecurityCode(CallId, "alice", localIsCaller = true)
        val crossedAccept = event("call.accept", JsonObject().apply {
            addProperty("crossed", true)
            addProperty("offerer", false)
        })

        controller.onSignalEvent(active(), crossedAccept)
        controller.onSignalEvent(active(), config(sasAllowed = true))

        assertTrue(signal.sent.none { it.type == "rtc.sas.commit" || it.type == "rtc.offer" })
        assertEquals(CallSecurityState.Establishing, states.last())
    }

    @Test
    fun crossedCallAdoptsCanonicalCallId() {
        val signal = CapturingSignalClient()
        val states = mutableListOf<CallSecurityState>()
        val controller = controller("bob", signal, sdp(0), sdp(32), states)
        controller.prepareSecurityCode("00000000-0000-0000-0000-000000000098", "alice", localIsCaller = true)
        controller.onSignalEvent(active(), event("call.accept", JsonObject().apply {
            addProperty("crossed", true)
            addProperty("offerer", false)
        }))
        controller.onSignalEvent(active(), config(sasAllowed = true))
        assertEquals(CallSecurityState.Establishing, states.last())
        val callerSignal = CapturingSignalClient()
        val callerStates = mutableListOf<CallSecurityState>()
        val caller = controller("alice", callerSignal, sdp(0), sdp(32), callerStates)
        caller.prepareSecurityCode(CallId, "bob", localIsCaller = true)
        caller.onSignalEvent(active(), event("call.accept"))
        caller.onSignalEvent(active(), config(sasAllowed = true))
        controller.onSignalEvent(active(), callerSignal.take("rtc.sas.commit"))
        controller.onSignalEvent(active(), callerSignal.take("rtc.offer"))
        caller.onSignalEvent(active(), signal.take("rtc.sas.key"))
        caller.onSignalEvent(active(), signal.take("rtc.answer"))
        controller.onSignalEvent(active(), callerSignal.take("rtc.sas.reveal"))
        caller.onTransportConnection(CallId, MediaConnectionState.Connected)
        controller.onTransportConnection(CallId, MediaConnectionState.Connected)
        assertTrue(states.last() is CallSecurityState.Ready)
        assertEquals(callerStates.last(), states.last())
    }

    @Test
    fun rejectsMediaFingerprintUsingAnotherAlgorithm() {
        val session = sdp(0)
        val media = "m=audio 9 UDP/TLS/RTP/SAVPF 111\r\n" +
            "a=fingerprint:sha-384 " + List(48) { "AB" }.joinToString(":") + "\r\n"
        assertTrue(runCatching { SdpFingerprint.sha256(session + media) }.isFailure)
    }

    @Test
    fun reconfigurationCannotEraseSecurityFailure() {
        val signal = CapturingSignalClient()
        val states = mutableListOf<CallSecurityState>()
        val controller = controller("alice", signal, sdp(0), sdp(32), states)
        controller.prepareSecurityCode(CallId, "bob", localIsCaller = true)
        controller.onSignalEvent(active(), event("call.accept"))
        controller.onSignalEvent(active(), config(sasAllowed = true))
        controller.onSignalEvent(active(), event("rtc.sas.commit", JsonObject().apply { addProperty("commitment", "A".repeat(43)) }))
        assertTrue(states.last() is CallSecurityState.Failed)
        controller.onSignalEvent(active(), config(sasAllowed = false))
        assertTrue(states.last() is CallSecurityState.Failed)
    }

    private fun controller(
        login: String,
        signal: CapturingSignalClient,
        offer: String,
        answer: String,
        states: MutableList<CallSecurityState>,
    ) = ForegroundCallController(
        signal = signal,
        mediaFactory = { _, _, _, _, _, _ -> FakeMediaSession(offer, answer) },
        ids = FixedIds(),
        accountId = AccountId("sas-test"),
        selfLogin = login,
        onSecurityStateChanged = { _, state -> states += state },
    )

    private fun active() = CallSnapshot(CallPhase.Active, CallId, 1, AccountId("sas-test"))

    private fun config(sasAllowed: Boolean) = event("rtc.config", JsonObject().apply {
        add("ice_servers", JsonArray())
        addProperty("call_sas_allowed", sasAllowed)
    })

    private fun event(type: String, payload: JsonObject = JsonObject()) =
        SignalEvent(EventId, CallId, type, 1_787_666_400_000, payload)

    private class CapturingSignalClient : SignalClient {
        val sent = mutableListOf<SignalEvent>()
        override fun send(event: SignalEvent, onSettled: (() -> Unit)?) {
            sent += event
        }

        fun take(type: String): SignalEvent {
            val index = sent.indexOfFirst { it.type == type }
            check(index >= 0) { "missing $type in ${sent.map { it.type }}" }
            return sent.removeAt(index)
        }
    }

    private class FixedIds : EventIds {
        private var next = 10
        override fun nextEventId(): String = "00000000-0000-0000-0000-${next++.toString().padStart(12, '0')}"
        override fun nextCallId(): String = CallId
        override fun nowMillis(): Long = 1_787_666_400_000
    }

    private class FakeMediaSession(
        private val offer: String,
        private val answer: String,
    ) : MediaSession {
        override suspend fun createOffer(): String = offer
        override suspend fun acceptOffer(sdp: String): String = answer
        override suspend fun setAnswer(sdp: String) = Unit
        override suspend fun addIceCandidate(candidate: IceCandidateData) = Unit
        override suspend fun removeIceCandidates(candidates: List<IceCandidateData>) = Unit
        override suspend fun restartIce(): String = offer
        override suspend fun updateIceServers(servers: List<IceServerData>) = Unit
        override fun beginRemoteDescription() = Unit
        override fun onNetworkChanged() = Unit
        override fun setMuted(muted: Boolean) = Unit
        override fun setActive(active: Boolean) = Unit
        override fun getStats(onResult: (CallStats) -> Unit) = Unit
        override suspend fun close() = Unit
    }

    companion object {
        private const val CallId = "00000000-0000-0000-0000-000000000099"
        private const val EventId = "00000000-0000-0000-0000-000000000001"

        private fun sdp(offset: Int): String =
            "v=0\r\na=fingerprint:sha-256 " +
                ByteArray(32) { (it + offset).toByte() }
                    .joinToString(":") { "%02X".format(it.toInt() and 0xff) } +
                "\r\n"
    }
}
