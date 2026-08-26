package org.tinitalk.telecom

import org.tinitalk.call.CallPhase
import org.tinitalk.call.CallSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class TelecomActionScopeTest {
    @Test
    fun acceptsUnexpiredMatchingPendingIncomingCallbackWhileIdle() {
        val now = Instant.parse("2026-08-26T10:00:00Z")

        assertTrue(TelecomActionScope.acceptsCallback(CallSnapshot(), "call-1", now.plusSeconds(1), null, "call-1", now))
        assertFalse(TelecomActionScope.acceptsSelection(CallSnapshot(), "call-1"))
    }

    @Test
    fun rejectsExpiredPendingIncomingCallbackWhileIdle() {
        val now = Instant.parse("2026-08-26T10:00:00Z")

        assertFalse(TelecomActionScope.acceptsCallback(CallSnapshot(), "call-1", now, null, "call-1", now))
        assertFalse(TelecomActionScope.acceptsCallback(CallSnapshot(), "call-1", now.minusSeconds(1), null, "call-1", now))
    }

    @Test
    fun rejectsUnrelatedCallbackWhileIdle() {
        val now = Instant.parse("2026-08-26T10:00:00Z")

        assertFalse(TelecomActionScope.acceptsCallback(CallSnapshot(), "call-1", now.plusSeconds(1), null, "other-call", now))
    }

    @Test
    fun acceptsOnlyCurrentActiveCallForSelection() {
        val active = CallSnapshot(CallPhase.Active, "call-1", 4)

        assertTrue(TelecomActionScope.acceptsCallback(active, null, null, "call-1", "call-1", Instant.parse("2026-08-26T10:00:00Z")))
        assertTrue(TelecomActionScope.acceptsSelection(active, "call-1"))
        assertFalse(TelecomActionScope.acceptsSelection(active, "other-call"))
    }

    @Test
    fun mapsCanonicalCallToItsLocalTelecomSession() {
        val active = CallSnapshot(CallPhase.Active, "canonical", 4)
        val now = Instant.parse("2026-08-26T10:00:00Z")

        assertTrue(TelecomActionScope.acceptsCallback(active, null, null, "local", "local", now))
        assertFalse(TelecomActionScope.acceptsCallback(active, null, null, "local", "canonical", now))
        assertTrue(TelecomActionScope.acceptsSelection(active, "canonical"))
        assertEquals("local", TelecomActionScope.telecomCallForSelection(active, "canonical", "local"))
        assertNull(TelecomActionScope.telecomCallForSelection(active, "other", "local"))
    }
}
