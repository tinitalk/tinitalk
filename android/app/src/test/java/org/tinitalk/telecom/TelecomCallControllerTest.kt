package org.tinitalk.telecom

import org.tinitalk.push.IncomingInvite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class TelecomCallControllerTest {
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
        cache.update("call-1", listOf("earpiece", "speaker"))

        assertEquals("speaker", cache.find("call-1", "speaker") { it })
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
        val invite = IncomingInvite("call-1", "Alice", Instant.parse("2026-08-26T10:00:30Z"))
        var answered = false
        var answerSucceeded = false
        var disconnected = false

        controller.addIncoming(invite, TelecomCallCallbacks(
            onAnswer = { answered = true },
            onDisconnect = { disconnected = true },
        ))
        registrar.onAnswer?.invoke()
        registrar.onDisconnect?.invoke()
        controller.answer("call-1") { answerSucceeded = it }
        controller.reject("call-1")

        assertEquals(invite, registrar.invite)
        assertTrue(answered)
        assertTrue(answerSucceeded)
        assertTrue(disconnected)
        assertEquals("call-1", registrar.answeredCall)
        assertEquals("call-1", registrar.rejectedCall)
    }

    @Test
    fun addsAndActivatesOutgoingCall() {
        val registrar = FakeTelecomRegistrar()
        val controller = TelecomCallController(registrar)
        var disconnected = false
        var activationSucceeded = false

        controller.addOutgoing("call-2", "Bob", TelecomCallCallbacks(onDisconnect = { disconnected = true }))
        registrar.outgoingDisconnect?.invoke()
        controller.setActive("call-2") { activationSucceeded = it }

        assertEquals("call-2", registrar.outgoingCallId)
        assertEquals("Bob", registrar.outgoingDisplayName)
        assertTrue(disconnected)
        assertTrue(activationSucceeded)
        assertEquals("call-2", registrar.activeCall)
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
            "call-1",
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
        controller.selectEndpoint("call-1", "speaker-id")

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
        var answeredCall: String? = null
        var rejectedCall: String? = null
        var outgoingCallId: String? = null
        var outgoingDisplayName: String? = null
        var outgoingDisconnect: (() -> Unit)? = null
        var activeCall: String? = null
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

        override fun addOutgoing(callId: String, displayName: String, callbacks: TelecomCallCallbacks) {
            outgoingCallId = callId
            outgoingDisplayName = displayName
            outgoingDisconnect = callbacks.onDisconnect
            onActive = callbacks.onActive
            onInactive = callbacks.onInactive
            onEndpointsChanged = callbacks.onEndpointsChanged
        }

        override fun answer(callId: String, onResult: (Boolean) -> Unit) {
            answeredCall = callId
            onResult(true)
        }

        override fun reject(callId: String) {
            rejectedCall = callId
        }

        override fun setActive(callId: String, onResult: (Boolean) -> Unit) {
            activeCall = callId
            onResult(true)
        }

        override fun selectEndpoint(callId: String, endpointId: String) {
            selectedEndpointId = endpointId
        }

        override fun cancel(callId: String) = Unit
    }
}
