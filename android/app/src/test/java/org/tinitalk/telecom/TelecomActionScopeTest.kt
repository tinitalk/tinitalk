package org.tinitalk.telecom

import org.tinitalk.call.AccountCallKey
import org.tinitalk.call.CallPhase
import org.tinitalk.call.CallSnapshot
import org.tinitalk.data.AccountId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class TelecomActionScopeTest {
    private val accountId = AccountId("account-a")
    private fun key(callId: String) = AccountCallKey(accountId, callId)
    private fun snapshot(phase: CallPhase = CallPhase.Idle, callId: String? = null, seq: Long = 0) =
        CallSnapshot(phase, callId, seq, accountId)
    @Test
    fun acceptsUnexpiredMatchingPendingIncomingCallbackWhileIdle() {
        val now = Instant.parse("2026-08-26T10:00:00Z")

        assertTrue(TelecomActionScope.acceptsCallback(snapshot(), key("call-1"), now.plusSeconds(1), null, key("call-1"), now))
        assertFalse(TelecomActionScope.acceptsSelection(snapshot(), key("call-1")))
    }

    @Test
    fun rejectsExpiredPendingIncomingCallbackWhileIdle() {
        val now = Instant.parse("2026-08-26T10:00:00Z")

        assertFalse(TelecomActionScope.acceptsCallback(snapshot(), key("call-1"), now, null, key("call-1"), now))
        assertFalse(TelecomActionScope.acceptsCallback(snapshot(), key("call-1"), now.minusSeconds(1), null, key("call-1"), now))
    }

    @Test
    fun rejectsUnrelatedCallbackWhileIdle() {
        val now = Instant.parse("2026-08-26T10:00:00Z")

        assertFalse(TelecomActionScope.acceptsCallback(snapshot(), key("call-1"), now.plusSeconds(1), null, key("other-call"), now))
    }

    @Test
    fun acceptsAudioSelectionDuringCurrentOutgoingAndActiveCall() {
        val connecting = snapshot(CallPhase.Connecting, "call-1", 4)
        val ringing = snapshot(CallPhase.Ringing, "call-1", 4)
        val active = snapshot(CallPhase.Active, "call-1", 4)

        assertTrue(TelecomActionScope.acceptsCallback(active, null, null, key("call-1"), key("call-1"), Instant.parse("2026-08-26T10:00:00Z")))
        assertTrue(TelecomActionScope.acceptsSelection(connecting, key("call-1")))
        assertTrue(TelecomActionScope.acceptsSelection(ringing, key("call-1")))
        assertTrue(TelecomActionScope.acceptsSelection(active, key("call-1")))
        assertFalse(TelecomActionScope.acceptsSelection(connecting, key("other-call")))
        assertFalse(TelecomActionScope.acceptsSelection(snapshot(CallPhase.Ended, "call-1", 4), key("call-1")))
    }

    @Test
    fun mapsCanonicalCallToItsLocalTelecomSession() {
        val active = snapshot(CallPhase.Active, "canonical", 4)
        val now = Instant.parse("2026-08-26T10:00:00Z")

        assertTrue(TelecomActionScope.acceptsCallback(active, null, null, key("local"), key("local"), now))
        assertFalse(TelecomActionScope.acceptsCallback(active, null, null, key("local"), key("canonical"), now))
        assertTrue(TelecomActionScope.acceptsSelection(active, key("canonical")))
        assertEquals(key("local"), TelecomActionScope.telecomCallForSelection(active, key("canonical"), key("local")))
        assertNull(TelecomActionScope.telecomCallForSelection(active, key("other"), key("local")))
    }

    @Test
    fun equalRawCallIdsFromDifferentAccountsNeverShareCallbacks() {
        val accountA = AccountId("account-a")
        val keyA = AccountCallKey(accountA, "same-call")
        val keyB = AccountCallKey(AccountId("account-b"), "same-call")
        val activeA = CallSnapshot(CallPhase.Active, "same-call", accountId = accountA)
        val now = Instant.parse("2026-08-26T10:00:00Z")

        assertTrue(TelecomActionScope.acceptsCallback(activeA, null, null, keyA, keyA, now))
        assertFalse(TelecomActionScope.acceptsCallback(activeA, null, null, keyA, keyB, now))
        assertFalse(TelecomActionScope.acceptsSelection(activeA, keyB))
    }
}
