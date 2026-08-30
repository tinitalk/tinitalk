package org.tinitalk.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryRefreshGateTest {
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
