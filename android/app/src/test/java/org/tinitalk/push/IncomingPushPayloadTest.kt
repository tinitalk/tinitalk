package org.tinitalk.push

import org.tinitalk.call.CallPhase
import org.tinitalk.call.CallSnapshot
import org.tinitalk.telecom.TerminalCallTombstones
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class IncomingPushPayloadTest {
    @Test
    fun acceptsFreshIncomingCallPayload() {
        val invite = IncomingPushPayload.parse(
            mapOf(
                "type" to "incoming_call",
                "call_id" to "call-1",
                "caller" to "Alice",
                "caller_login" to "alice",
                "last_seq" to "7",
                "expires_at" to "2026-08-26T10:00:30Z",
            ),
            now = Instant.parse("2026-08-26T10:00:00Z"),
        )

        assertEquals("call-1", invite?.callId)
        assertEquals("Alice", invite?.caller)
        assertEquals("alice", invite?.callerLogin)
        assertEquals(7L, invite?.lastSeq)
    }

    @Test
    fun ignoresExpiredOrUnknownPayload() {
        assertNull(IncomingPushPayload.parse(mapOf("type" to "other"), Instant.parse("2026-08-26T10:00:00Z")))
        assertNull(
            IncomingPushPayload.parse(
                mapOf("type" to "incoming_call", "call_id" to "call-1", "expires_at" to "2026-08-26T09:59:59Z"),
                Instant.parse("2026-08-26T10:00:00Z"),
            ),
        )
    }

    @Test
    fun detectsCancelPayload() {
        assertEquals(PushAction.Cancel, IncomingPushPayload.action(mapOf("type" to "call_cancel")))
    }

    @Test
    fun acceptedCallDoesNotDismissItsOwnActiveSession() {
        val cancel = IncomingPushPayload.cancellation(
            mapOf("type" to "call_cancel", "call_id" to "call-1", "call_event" to "call.accept"),
        )!!

        assertFalse(cancel.shouldDismiss("call-1", CallSnapshot(CallPhase.Active, "call-1")))
    }

    @Test
    fun acceptedCallDismissesAnotherDeviceStillRinging() {
        val cancel = CallCancellation("call-1", "call.accept")

        assertTrue(cancel.shouldDismiss("call-1", CallSnapshot()))
    }

    @Test
    fun staleCancellationDoesNotTouchTheNextInvite() {
        val cancel = CallCancellation("old-call", "call.cancel")

        assertFalse(cancel.shouldDismiss("new-call", CallSnapshot(CallPhase.Ringing, "new-call")))
    }

    @Test
    fun onlyUnansweredMatchingCancellationIsMissed() {
        assertTrue(
            CallCancellation("call-1", "call.cancel")
                .shouldShowMissed("call-1", CallSnapshot(CallPhase.Ringing, "call-1")),
        )
        assertFalse(
            CallCancellation("call-1", "call.accept")
                .shouldShowMissed("call-1", CallSnapshot()),
        )
        assertFalse(
            CallCancellation("call-1", "call.reject")
                .shouldShowMissed("call-1", CallSnapshot()),
        )
        assertFalse(
            CallCancellation("old-call", "call.cancel")
                .shouldShowMissed("new-call", CallSnapshot(CallPhase.Ringing, "new-call")),
        )
    }

    @Test
    fun terminalBeforeInviteSuppressesOnlyMatchingCallUntilExpiry() {
        val remembered = TerminalCallTombstones.remember(emptySet(), "call-1", nowMillis = 1_000)

        assertTrue(TerminalCallTombstones.contains(remembered, "call-1", nowMillis = 1_001))
        assertFalse(TerminalCallTombstones.contains(remembered, "call-2", nowMillis = 1_001))
        assertFalse(TerminalCallTombstones.contains(remembered, "call-1", nowMillis = 121_001))
    }

    @Test
    fun incomingCallEntersForegroundBeforeFullScreenLaunch() {
        val steps = mutableListOf<String>()
        val invite = IncomingInvite("call-1", "Alice", Instant.parse("2026-08-26T10:00:30Z"))

        IncomingCallForegroundPresentation(
            enterForeground = { steps += "foreground" },
            openFullScreen = { steps += "full_screen" },
        ).present(invite)

        assertEquals(listOf("foreground", "full_screen"), steps)
    }

    @Test
    fun fullScreenStartsVibrationAndRingtoneBeforeRemovingNotification() {
        val steps = mutableListOf<String>()
        val invite = IncomingInvite("call-1", "Alice", Instant.parse("2026-08-26T10:00:30Z"))

        IncomingCallAlertHandoff(
            startVibration = { steps += "vibration" },
            startRingtone = { steps += "ringtone" },
            dismissNotification = { steps += "dismiss" },
        ).fullScreenShown(invite)

        assertEquals(listOf("vibration", "ringtone", "dismiss"), steps)
    }
}
