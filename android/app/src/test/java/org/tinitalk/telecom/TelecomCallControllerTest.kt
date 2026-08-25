package org.tinitalk.telecom

import org.tinitalk.push.IncomingInvite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class TelecomCallControllerTest {
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
        var disconnected = false

        controller.addIncoming(invite, { answered = true }, { disconnected = true })
        registrar.onAnswer?.invoke()
        registrar.onDisconnect?.invoke()
        controller.answer("call-1")
        controller.reject("call-1")

        assertEquals(invite, registrar.invite)
        assertTrue(answered)
        assertTrue(disconnected)
        assertEquals("call-1", registrar.answeredCall)
        assertEquals("call-1", registrar.rejectedCall)
    }

    @Test
    fun addsAndActivatesOutgoingCall() {
        val registrar = FakeTelecomRegistrar()
        val controller = TelecomCallController(registrar)
        var disconnected = false

        controller.addOutgoing("call-2", "Bob") { disconnected = true }
        registrar.outgoingDisconnect?.invoke()
        controller.setActive("call-2")

        assertEquals("call-2", registrar.outgoingCallId)
        assertEquals("Bob", registrar.outgoingDisplayName)
        assertTrue(disconnected)
        assertEquals("call-2", registrar.activeCall)
    }

    private class FakeTelecomRegistrar : TelecomRegistrar {
        var registered: TelecomCapabilities? = null
        var invite: IncomingInvite? = null
        var onAnswer: (() -> Unit)? = null
        var onDisconnect: (() -> Unit)? = null
        var answeredCall: String? = null
        var rejectedCall: String? = null
        var outgoingCallId: String? = null
        var outgoingDisplayName: String? = null
        var outgoingDisconnect: (() -> Unit)? = null
        var activeCall: String? = null

        override fun register(capabilities: TelecomCapabilities) {
            registered = capabilities
        }

        override fun addIncoming(invite: IncomingInvite, onAnswer: () -> Unit, onDisconnect: () -> Unit) {
            this.invite = invite
            this.onAnswer = onAnswer
            this.onDisconnect = onDisconnect
        }

        override fun addOutgoing(callId: String, displayName: String, onDisconnect: () -> Unit) {
            outgoingCallId = callId
            outgoingDisplayName = displayName
            outgoingDisconnect = onDisconnect
        }

        override fun answer(callId: String) {
            answeredCall = callId
        }

        override fun reject(callId: String) {
            rejectedCall = callId
        }

        override fun setActive(callId: String) {
            activeCall = callId
        }

        override fun cancel(callId: String) = Unit
    }
}
