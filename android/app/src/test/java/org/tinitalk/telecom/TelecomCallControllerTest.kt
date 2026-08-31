package org.tinitalk.telecom

import org.tinitalk.call.AccountCallKey
import org.tinitalk.call.CallSessionBinding
import org.tinitalk.data.AccountId
import org.tinitalk.push.IncomingInvite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class TelecomCallControllerTest {
    private val accountId = AccountId("account-a")
    private fun key(callId: String) = AccountCallKey(accountId, callId)
    @Test
    fun telecomFailureIsNonTerminalButExpiryIsTerminal() {
        var presentationFinishes = 0
        val failures = IncomingTelecomFailureHandler(
            finishPresentation = { presentationFinishes++ },
            reportTelecomFailure = {},
        )

        failures.telecomFailed(IllegalStateException("legacy Telecom rejected the call"))
        assertEquals(0, presentationFinishes)

        failures.callExpired()
        assertEquals(1, presentationFinishes)
    }

    @Test
    fun cachesEndpointsForSelectionWithoutAnotherFlowEmission() {
        val cache = EndpointCache<String>()
        val keyA = AccountCallKey(AccountId("account-a"), "same-call")
        val keyB = AccountCallKey(AccountId("account-b"), "same-call")
        cache.update(keyA, listOf("earpiece", "speaker"))
        cache.update(keyB, listOf("bluetooth"))

        assertEquals("speaker", cache.find(keyA, "speaker") { it })
        assertEquals("bluetooth", cache.find(keyB, "bluetooth") { it })
    }

    @Test
    fun pendingCallActionFinishesReceiverOnlyOnce() {
        var finishes = 0
        val pending = PendingCallAction { finishes++ }

        pending.finish()
        pending.finish()

        assertEquals(1, finishes)
    }

    @Test
    fun registersAudioOnlyCapabilities() {
        val registrar = FakeTelecomRegistrar()
        val controller = TelecomCallController(registrar)

        controller.registerAudioOnly()

        assertEquals(TelecomCapabilities.AudioOnly, registrar.registered)
    }

    @Test
    fun addsAndControlsIncomingCall() {
        val registrar = FakeTelecomRegistrar()
        val controller = TelecomCallController(registrar)
        val invite = IncomingInvite(
            accountId,
            CallSessionBinding("https://a.example", "alice", "session-a", "config-a"),
            "call-1",
            "Alice",
            Instant.parse("2026-08-26T10:00:30Z"),
        )
        var answered = false
        var answerSucceeded = false
        var disconnected = false

        controller.addIncoming(invite, TelecomCallCallbacks(
            onAnswer = { answered = true },
            onDisconnect = { disconnected = true },
        ))
        registrar.onAnswer?.invoke()
        registrar.onDisconnect?.invoke()
        controller.answer(invite.key) { answerSucceeded = it }
        controller.reject(invite.key)

        assertEquals(invite, registrar.invite)
        assertTrue(answered)
        assertTrue(answerSucceeded)
        assertTrue(disconnected)
        assertEquals(invite.key, registrar.answeredCall)
        assertEquals(invite.key, registrar.rejectedCall)
    }

    @Test
    fun addsAndActivatesOutgoingCall() {
        val registrar = FakeTelecomRegistrar()
        val controller = TelecomCallController(registrar)
        var disconnected = false
        var activationSucceeded = false

        controller.addOutgoing(key("call-2"), "Bob", TelecomCallCallbacks(onDisconnect = { disconnected = true }))
        registrar.outgoingDisconnect?.invoke()
        controller.setActive(key("call-2")) { activationSucceeded = it }

        assertEquals(key("call-2"), registrar.outgoingCallId)
        assertEquals("Bob", registrar.outgoingDisplayName)
        assertTrue(disconnected)
        assertTrue(activationSucceeded)
        assertEquals(key("call-2"), registrar.activeCall)
    }

    @Test
    fun forwardsActivityEndpointCallbacksAndEndpointSelection() {
        val registrar = FakeTelecomRegistrar()
        val controller = TelecomCallController(registrar)
        var active = false
        var inactive = false
        var routes: AudioEndpointState? = null
        val expectedRoutes = AudioEndpointState(
            current = AudioEndpoint("earpiece-id", "Earpiece", 1),
            available = listOf(
                AudioEndpoint("earpiece-id", "Earpiece", 1),
                AudioEndpoint("speaker-id", "Speaker", 4),
            ),
        )

        controller.addOutgoing(
            key("call-1"),
            "Bob",
            TelecomCallCallbacks(
                onDisconnect = {},
                onActive = { active = true },
                onInactive = { inactive = true },
                onEndpointsChanged = { routes = it },
            ),
        )
        registrar.onActive?.invoke()
        registrar.onInactive?.invoke()
        registrar.onEndpointsChanged?.invoke(expectedRoutes)
        controller.selectEndpoint(key("call-1"), "speaker-id")

        assertTrue(active)
        assertTrue(inactive)
        assertEquals(expectedRoutes, routes)
        assertEquals("speaker-id", registrar.selectedEndpointId)
    }

    private class FakeTelecomRegistrar : TelecomRegistrar {
        var registered: TelecomCapabilities? = null
        var invite: IncomingInvite? = null
        var onAnswer: (() -> Unit)? = null
        var onDisconnect: (() -> Unit)? = null
        var onActive: (() -> Unit)? = null
        var onInactive: (() -> Unit)? = null
        var onEndpointsChanged: ((AudioEndpointState) -> Unit)? = null
        var answeredCall: AccountCallKey? = null
        var rejectedCall: AccountCallKey? = null
        var outgoingCallId: AccountCallKey? = null
        var outgoingDisplayName: String? = null
        var outgoingDisconnect: (() -> Unit)? = null
        var activeCall: AccountCallKey? = null
        var selectedEndpointId: String? = null

        override fun register(capabilities: TelecomCapabilities) {
            registered = capabilities
        }

        override fun addIncoming(invite: IncomingInvite, callbacks: TelecomCallCallbacks) {
            this.invite = invite
            onAnswer = callbacks.onAnswer
            onDisconnect = callbacks.onDisconnect
            onActive = callbacks.onActive
            onInactive = callbacks.onInactive
            onEndpointsChanged = callbacks.onEndpointsChanged
        }

        override fun addOutgoing(key: AccountCallKey, displayName: String, callbacks: TelecomCallCallbacks) {
            outgoingCallId = key
            outgoingDisplayName = displayName
            outgoingDisconnect = callbacks.onDisconnect
            onActive = callbacks.onActive
            onInactive = callbacks.onInactive
            onEndpointsChanged = callbacks.onEndpointsChanged
        }

        override fun answer(key: AccountCallKey, onResult: (Boolean) -> Unit) {
            answeredCall = key
            onResult(true)
        }

        override fun reject(key: AccountCallKey) {
            rejectedCall = key
        }

        override fun setActive(key: AccountCallKey, onResult: (Boolean) -> Unit) {
            activeCall = key
            onResult(true)
        }

        override fun selectEndpoint(key: AccountCallKey, endpointId: String) {
            selectedEndpointId = endpointId
        }

        override fun cancel(key: AccountCallKey) = Unit
    }
}
