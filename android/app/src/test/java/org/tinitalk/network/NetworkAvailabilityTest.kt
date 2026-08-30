package org.tinitalk.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkAvailabilityTest {
    @Test
    fun successorNetworkPreventsTransientOfflineState() {
        val changes = mutableListOf<Boolean>()
        val state = NetworkAvailabilityState("wifi", changes::add)

        assertTrue(state.onLost("wifi"))
        state.onAvailable("mobile")
        state.confirmUnavailable()

        assertTrue(state.available)
        assertEquals(emptyList<Boolean>(), changes)
    }

    @Test
    fun confirmedLossOfCurrentNetworkPublishesOfflineState() {
        val changes = mutableListOf<Boolean>()
        val state = NetworkAvailabilityState("wifi", changes::add)

        assertTrue(state.onLost("wifi"))
        state.confirmUnavailable()

        assertFalse(state.available)
        assertEquals(listOf(false), changes)
    }

    @Test
    fun firstNetworkAfterOfflineStartupPublishesAvailableState() {
        val changes = mutableListOf<Boolean>()
        val state = NetworkAvailabilityState<String>(null, changes::add)

        state.onAvailable("mobile")

        assertTrue(state.available)
        assertEquals(listOf(true), changes)
    }

    @Test
    fun registrationSnapshotCorrectsAStaleInitialNetwork() {
        val changes = mutableListOf<Boolean>()
        val state = NetworkAvailabilityState("old", changes::add)

        state.synchronize(null)

        assertFalse(state.available)
        assertEquals(listOf(false), changes)
    }
}
