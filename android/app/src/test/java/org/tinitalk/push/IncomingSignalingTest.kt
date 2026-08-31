package org.tinitalk.push

import org.tinitalk.call.CallCoordinator
import org.tinitalk.call.CallSessionBinding
import org.tinitalk.call.SignalClient
import org.tinitalk.data.AccountId
import org.tinitalk.data.signal.SignalEvent
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class IncomingSignalingTest {
    @Test
    fun competingInviteIsRejectedWithoutRingingThroughItsOwnRawCallId() {
        val signal = RecordingSignalClient()
        val accountId = AccountId("account-b")
        val invite = IncomingInvite(
            accountId = accountId,
            sessionBinding = CallSessionBinding("https://b.example", "bob", "session-b", "config-b"),
            callId = "same-call",
            caller = "Alice",
            expiresAt = Instant.now().plusSeconds(30),
            lastSeq = 7,
        )
        val coordinator = CallCoordinator("bob", signal, accountId = accountId)

        rejectCompetingInvite(coordinator, invite)

        assertEquals(listOf("call.resume", "call.reject"), signal.events.map { it.type })
        assertEquals(listOf("same-call", "same-call"), signal.events.map { it.callId })
        assertEquals(7L, signal.events.first().payload["last_seq"].asLong)
    }

    private class RecordingSignalClient : SignalClient {
        val events = mutableListOf<SignalEvent>()

        override fun send(event: SignalEvent, onSettled: (() -> Unit)?) {
            events += event
        }
    }
}
