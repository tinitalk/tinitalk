package org.tinitalk.call

import org.tinitalk.media.MediaConnectionState
import org.tinitalk.data.AccountId
import org.tinitalk.data.ContactAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallUiStateTest {
    private val accountId = AccountId("account-a")
    private fun key(callId: String) = AccountCallKey(accountId, callId)
    @Test
    fun durationStartsOnFirstMediaConnectionAndKeepsItsAnchorAfterReconnect() {
        var state = CallUiState(phase = CallPhase.Active)

        assertNull(state.durationMillis(5_000L))
        state = state.onMediaConnection(MediaConnectionState.Connected, 5_000L)
        state = state.onMediaConnection(MediaConnectionState.Disconnected, 8_000L)
        state = state.onMediaConnection(MediaConnectionState.Connected, 10_000L)

        assertEquals(5_000L, state.connectedAtElapsedMs)
        assertEquals(7_000L, state.durationMillis(12_000L))
        assertEquals(ConnectionHealth.Good, state.connectionHealth)
    }

    @Test
    fun endedCallFreezesConnectedDuration() {
        val connected = CallUiState(phase = CallPhase.Active)
            .onMediaConnection(MediaConnectionState.Connected, 1_000L)

        val ended = connected.onEnded(CallEndReason.RemoteHangup, 61_999L)

        assertEquals(60_999L, ended.durationMillis(500_000L))
        assertEquals("01:00", ended.durationText(500_000L))
    }

    @Test
    fun durationUsesHoursOnlyWhenNeeded() {
        assertEquals("00:00", formatCallDuration(0L))
        assertEquals("04:09", formatCallDuration(249_999L))
        assertEquals("1:04:09", formatCallDuration(3_849_999L))
    }

    @Test
    fun delayedResetCannotClearTheNextCall() {
        CallUiStateStore.begin(key("new-call"), CallPeer("Alice"), CallDirection.Incoming, CallPhase.Ringing)

        assertFalse(CallUiStateStore.reset(key("old-call")))
        assertEquals("new-call", CallUiStateStore.snapshot().callId)
        assertTrue(CallUiStateStore.reset(key("new-call")))
    }

    @Test
    fun cancelledOutgoingCallIsNotTurnedBackIntoConnecting() {
        val address = ContactAddress.of("https://example.com", "bob")
        val ended = CallUiState(
            callId = "call-1",
            peer = CallPeer("Bob", "bob", address),
            direction = CallDirection.Outgoing,
            phase = CallPhase.Ended,
            endReason = CallEndReason.Cancelled,
        )

        val visible = outgoingVisibleState(ended, "bob", "Bob", address)

        assertEquals(CallPhase.Ended, visible.phase)
        assertEquals(address, visible.peer?.contactAddress)
    }

    @Test
    fun outgoingVisibleStateCarriesProvidedContactAddress() {
        val address = ContactAddress.of("https://calls.example", "bob")

        val visible = outgoingVisibleState(CallUiState(), "bob", "Bob", address)

        assertEquals(address, visible.peer?.contactAddress)
    }

    @Test
    fun callPeerDoesNotInferAddressFromDisplayName() {
        val peer = CallPeer(displayName = "Grandma")

        assertNull(peer.contactAddress)
    }

    @Test
    fun callPeerAddressSeparatesSameLoginOnDifferentServers() {
        val first = CallPeer("Alex", "alex", ContactAddress.of("https://one.example", "alex"))
        val second = CallPeer("Alex", "alex", ContactAddress.of("https://two.example", "alex"))

        assertFalse(first.contactAddress == second.contactAddress)
    }

    @Test
    fun visibleIncomingCallScreenDismissesOnlyItsSystemOverlay() {
        val incoming = CallUiState(direction = CallDirection.Incoming, phase = CallPhase.Ringing)
        val outgoing = CallUiState(direction = CallDirection.Outgoing, phase = CallPhase.Ringing)

        assertTrue(shouldDismissIncomingOverlay(activityVisible = true, incoming))
        assertFalse(shouldDismissIncomingOverlay(activityVisible = false, incoming))
        assertFalse(shouldDismissIncomingOverlay(activityVisible = true, outgoing))
    }

    @Test
    fun activeIncomingCallReleasesAnswerLockButKeepsHangupLock() {
        val gate = CallScreenActionGate()

        assertTrue(gate.lock(CallScreenAction.Answer, key("call-1")))
        assertFalse(gate.lock(CallScreenAction.Answer, key("call-1")))
        assertFalse(gate.lock(CallScreenAction.Reject, key("call-1")))

        gate.onCallState(CallUiState(accountId = accountId, callId = "call-1", phase = CallPhase.Active))

        assertTrue(gate.lock(CallScreenAction.End, key("call-1")))
        gate.onCallState(CallUiState(accountId = accountId, callId = "call-1", phase = CallPhase.Active))
        assertFalse(gate.lock(CallScreenAction.End, key("call-1")))
    }
}
