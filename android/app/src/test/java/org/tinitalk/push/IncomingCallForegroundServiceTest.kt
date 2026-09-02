package org.tinitalk.push

import org.tinitalk.call.AccountCallKey
import org.tinitalk.call.CallSessionBinding
import org.tinitalk.data.AccountId
import java.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingCallForegroundServiceTest {
    private val accountId = AccountId("account-a")
    private val binding = CallSessionBinding("https://a.example", "alice", "session-a", "config-a")

    @Test
    fun foregroundNotificationIsRemovedOnlyAfterTerminalOrMissingPending() {
        val now = Instant.parse("2026-08-31T10:00:00Z")
        val pending = invite("call-foreground", expiresAt = now.plusSeconds(30))
        val replacement = invite("call-replacement", expiresAt = now.plusSeconds(30))

        assertFalse(
            shouldRemoveIncomingForegroundNotification(
                presentedOwner = pending.owner,
                pendingInvite = pending,
                terminal = false,
                now = now,
            ),
        )
        assertTrue(
            shouldRemoveIncomingForegroundNotification(
                presentedOwner = pending.owner,
                pendingInvite = pending,
                terminal = true,
                now = now,
            ),
        )
        assertTrue(
            shouldRemoveIncomingForegroundNotification(
                presentedOwner = pending.owner,
                pendingInvite = null,
                terminal = false,
                now = now,
            ),
        )
        assertTrue(
            shouldRemoveIncomingForegroundNotification(
                presentedOwner = pending.owner,
                pendingInvite = pending.copy(expiresAt = now),
                terminal = false,
                now = now,
            ),
        )
        assertTrue(
            shouldRemoveIncomingForegroundNotification(
                presentedOwner = pending.owner,
                pendingInvite = replacement,
                terminal = false,
                now = now,
            ),
        )
    }

    private fun invite(callId: String, expiresAt: Instant): IncomingInvite {
        val key = AccountCallKey(accountId, callId)
        return IncomingInvite(
            accountId = accountId,
            sessionBinding = binding,
            callId = key.callId,
            caller = "Alice",
            callerLogin = "alice",
            expiresAt = expiresAt,
        )
    }
}
