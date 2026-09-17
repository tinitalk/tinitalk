package org.tinitalk.push

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import org.tinitalk.call.SequencedSignalEvent
import org.tinitalk.data.AccountId
import org.tinitalk.data.AccountRecord
import org.tinitalk.data.Session
import org.tinitalk.data.signal.SignalEvent
import java.time.Instant

class IncomingSocketPayloadTest {
    private val now = Instant.parse("2026-09-17T12:00:00Z")
    private val account = AccountRecord(AccountId("a"), Session("https://talk.example", "alice", "token", sessionId = "s"))
    private val callId = "018f7d51-40a1-7bb5-a2d0-7e47f9182000"

    @Test fun usesPrivateNameAndOriginalDeadlineForLiveAndRecoveredInvites() {
        val event = SequencedSignalEvent(SignalEvent(callId, callId, "call.incoming", now.minusSeconds(10).toEpochMilli(),
            JsonObject().apply { addProperty("caller_login", "bob"); addProperty("caller_name", "Admin private name") }), 1)
        val invite = requireNotNull(IncomingSocketPayload.fromEvent(account, event, { "Папа" }, now))
        assertEquals("Папа", invite.caller)
        assertEquals("bob", invite.callerLogin)
        assertEquals(now.plusSeconds(35), invite.expiresAt)
        assertEquals(1, invite.lastSeq)
        assertEquals("s", invite.sessionBinding.sessionId)
        assertNull(IncomingSocketPayload.fromEvent(account, event, { "Папа" }, now.plusSeconds(36)))
        val snapshot = JsonParser.parseString("""{"call_id":"$callId","caller_login":"bob","started_at":"2026-09-17T11:59:50Z","expires_at":"2026-09-17T12:00:35Z","last_seq":1}""").asJsonObject
        assertEquals(invite, IncomingSocketPayload.fromSnapshot(account, snapshot, { "Папа" }, now))
        assertEquals("bob", IncomingSocketPayload.fromSnapshot(account, snapshot, { null }, now)?.caller)
        snapshot.remove("expires_at")
        assertNull(IncomingSocketPayload.fromSnapshot(account, snapshot, { null }, now))
    }
}
