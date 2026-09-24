package org.tinitalk.call

import org.junit.Assert.*
import org.junit.Test
import org.tinitalk.data.AccountId
import org.tinitalk.push.IncomingInvite
import java.time.Instant

class WaitingCallRegistryTest {
    private fun invite(id: String, account: String = "one", session: String = "session") = IncomingInvite(
        AccountId(account), CallSessionBinding("https://$account.example", "me", session, null),
        id, "Caller", Instant.parse("2026-09-24T12:00:45Z"), "caller", 1,
    )

    @Test fun duplicateDoesNotRestartDeadline() {
        val registry = WaitingCallRegistry()
        val invite = invite("call")
        assertTrue(registry.add(invite, 0, 45_000))
        assertTrue(registry.add(invite, 10_000, 45_000))
        registry.acknowledge(invite.owner, 2, 15_000, 10_000)
        assertEquals(15_000, registry.get(invite.owner)!!.deadlineElapsedMs)
        assertEquals(listOf(invite), registry.expired(15_000).map { it.invite })
    }

    @Test fun callsOnDifferentServersHaveIndependentIdentityAndDeadlines() {
        val registry = WaitingCallRegistry()
        val first = invite("same-id", "one")
        val second = invite("same-id", "two")
        assertTrue(registry.add(first, 0, 45_000))
        assertTrue(registry.add(second, 5_000, 45_000))
        assertEquals(listOf(first), registry.expired(15_000).map { it.invite })
        registry.remove(first.owner)
        assertEquals(listOf(second), registry.snapshot().map { it.invite })
    }

    @Test fun onlyOneSelectionCanBeInFlight() {
        val registry = WaitingCallRegistry()
        val first = invite("first")
        val second = invite("second")
        listOf(first, second).forEach { registry.add(it, 0, 45_000); registry.acknowledge(it.owner, 2, 15_000, 0) }
        val selected = registry.select(first.owner, null, 100)!!
        assertNull(registry.select(second.owner, null, 100))
        registry.remove(first.owner)
        assertFalse(registry.isSelected(selected))
        val next = registry.select(second.owner, null, 100)!!
        registry.clearSelection(selected)
        assertTrue(registry.isSelected(next))
    }

    @Test fun cannotSelectUnconfirmedExpiredOrRemovedInvite() {
        val registry = WaitingCallRegistry()
        val invite = invite("call")
        registry.add(invite, 0, 45_000)
        assertNull(registry.select(invite.owner, null, 0))
        registry.acknowledge(invite.owner, 2, 15_000, 0)
        assertNull(registry.select(invite.owner, null, 15_000))
        registry.remove(invite.owner)
        assertNull(registry.select(invite.owner, null, 10))
    }

    @Test fun staleSessionAndSequenceCannotModifyEntry() {
        val registry = WaitingCallRegistry()
        val invite = invite("call")
        val replacement = invite("call", session = "new-session")
        registry.add(invite, 0, 45_000)
        assertFalse(registry.add(replacement, 0, 45_000))
        assertFalse(registry.acknowledge(replacement.owner, 20, 1, 0))
        assertFalse(registry.acknowledge(invite.owner, 1, 1, 0))
        assertNull(registry.remove(replacement.owner))
        assertNotNull(registry.get(invite.owner))
    }

    @Test fun globalLimitDoesNotDependOnServer() {
        val registry = WaitingCallRegistry(limit = 2)
        assertTrue(registry.add(invite("one", "one"), 0, 45_000))
        assertTrue(registry.add(invite("two", "two"), 0, 45_000))
        assertFalse(registry.add(invite("three", "three"), 0, 45_000))
    }

    @Test fun originalRingDeadlineCanOnlyBeRestoredByConfirmedPromotion() {
        val registry = WaitingCallRegistry()
        val invite = invite("call")
        registry.add(invite, 1_000, 40_000)
        registry.acknowledge(invite.owner, 2, 15_000, 1_000)
        registry.acknowledge(invite.owner, 3, 45_000, 8_000)
        assertEquals(16_000, registry.get(invite.owner)!!.deadlineElapsedMs)
        registry.acknowledge(invite.owner, 4, 45_000, 8_000, waiting = false)
        assertEquals(41_000, registry.get(invite.owner)!!.deadlineElapsedMs)
        assertFalse(registry.get(invite.owner)!!.waiting)
        registry.acknowledge(invite.owner, 5, 45_000, 10_000, waiting = false)
        assertEquals(41_000, registry.get(invite.owner)!!.deadlineElapsedMs)
        assertFalse(registry.acknowledge(invite.owner, 3, 1, 10_000))
        assertFalse(registry.get(invite.owner)!!.waiting)
    }

    @Test fun staleCancellationCannotRemovePendingCallOrNewSelection() {
        val registry = WaitingCallRegistry()
        val invite = invite("call")
        registry.add(invite, 0, 45_000)
        registry.acknowledge(invite.owner, 4, 15_000, 0)
        val selection = registry.select(invite.owner, null, 0)!!
        assertNull(registry.removeTerminal(invite.owner, 3))
        assertTrue(registry.isSelected(selection))
        assertNotNull(registry.removeTerminal(invite.owner, 5))
        assertFalse(registry.isSelected(selection))
    }
}
