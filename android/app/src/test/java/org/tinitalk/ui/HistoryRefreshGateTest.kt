package org.tinitalk.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryRefreshGateTest {
    @Test
    fun `background history is not treated as visible to the user`() {
        assertFalse(isHistoryVisibleToUser(activityResumed = false, historySelected = true))
        assertTrue(isHistoryVisibleToUser(activityResumed = true, historySelected = true))
        assertFalse(isHistoryVisibleToUser(activityResumed = true, historySelected = false))
    }

    @Test
    fun `only an explicit visit marks visible history as read`() {
        assertFalse(
            shouldMarkHistoryRead(
                userInitiated = false,
                activityResumed = true,
                historySelected = true,
            ),
        )
        assertTrue(
            shouldMarkHistoryRead(
                userInitiated = true,
                activityResumed = true,
                historySelected = true,
            ),
        )
    }

    @Test
    fun `history change during loading is refreshed once after loading finishes`() {
        val gate = HistoryRefreshGate()

        assertFalse(gate.request(loading = true))
        assertTrue(gate.afterLoad())
        assertFalse(gate.afterLoad())
    }

    @Test
    fun `history change starts refresh immediately when idle`() {
        val gate = HistoryRefreshGate()

        assertTrue(gate.request(loading = false))
        assertFalse(gate.afterLoad())
    }

    @Test
    fun `closing history forgets a pending refresh`() {
        val gate = HistoryRefreshGate()

        gate.request(loading = true)
        gate.clear()

        assertFalse(gate.afterLoad())
    }
}
