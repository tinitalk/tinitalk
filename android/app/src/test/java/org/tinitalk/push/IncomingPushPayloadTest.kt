package org.tinitalk.push

import org.tinitalk.data.AccountId
import org.tinitalk.data.AccountRecord
import org.tinitalk.data.AuthStore
import org.tinitalk.data.MemoryKeyValueStore
import org.tinitalk.data.PrefixTokenCipher
import org.tinitalk.data.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class IncomingPushPayloadTest {
    @Test
    fun sessionReplacementInvalidatesOnlyTheSelectedAccount() {
        val ids = ArrayDeque(listOf(AccountId("account-a"), AccountId("account-b")))
        val auth = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher()) { ids.removeFirst() }
        val accountA = auth.upsert(Session("https://a.example", "alice", "a", sessionId = "session-a"))
        val accountB = auth.upsert(Session("https://b.example", "bob", "b", sessionId = "session-b"))
        val cleaned = mutableListOf<AccountId>()

        assertTrue(invalidateReplacedAccount(accountA, auth, cleaned::add))

        assertNull(auth.get(accountA.id))
        assertEquals(accountB, auth.get(accountB.id))
        assertEquals(listOf(accountA.id), cleaned)
    }

    @Test
    fun managedPayloadRequiresExactAccountTarget() {
        val session = Session("https://a.example", "alice", "token", sessionId = "session-a")
        val target = mapOf(
            "target_login" to "alice",
            "target_device_id" to "phone",
            "target_session_id" to "session-a",
        )

        assertTrue(IncomingPushPayload.matchesTarget(target, session, "phone"))
        assertFalse(IncomingPushPayload.matchesTarget(target, session, "other-phone"))
        assertFalse(IncomingPushPayload.matchesTarget(target - "target_login", session, "phone"))
    }

    @Test
    fun parsesOnlyFreshIncomingCallForProvidedAccount() {
        val account = AccountRecord(
            AccountId("account-a"),
            Session("https://a.example", "alice", "token", sessionId = "session-a"),
        )
        val now = Instant.parse("2026-01-01T00:00:00Z")
        val data = mapOf(
            "type" to "incoming_call",
            "call_id" to "call-1",
            "caller" to "Bob",
            "expires_at" to "2026-01-01T00:00:30Z",
        )

        assertEquals(account.id, IncomingPushPayload.parse(data, account, now)?.accountId)
        assertNull(IncomingPushPayload.parse(data, account, Instant.parse("2026-01-01T00:00:31Z")))
    }

    @Test
    fun sessionReplacementRequiresExactLoginDeviceAndSession() {
        val session = Session("https://a.example", "alice", "token", sessionId = "session-a")
        val replacement = IncomingPushPayload.sessionReplacement(
            mapOf(
                "type" to "session_replaced",
                "login" to "alice",
                "revoked_device_id" to "phone",
                "revoked_session_id" to "session-a",
            ),
        )

        assertTrue(requireNotNull(replacement).matches(session, "phone"))
        assertFalse(replacement.matches(session, "other-phone"))
    }
}
