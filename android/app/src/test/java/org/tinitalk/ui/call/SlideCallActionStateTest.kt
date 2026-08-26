package org.tinitalk.ui.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlideCallActionStateTest {
    @Test
    fun `release springs back below threshold commits at threshold and locks action`() {
        val belowThreshold = SlideCallActionState()
            .dragBy(0.64f)
            .release()

        assertFalse(belowThreshold.committed)
        assertEquals(0f, belowThreshold.state.progress)
        assertFalse(belowThreshold.state.locked)

        val committed = belowThreshold.state
            .dragBy(0.68f)
            .release()

        assertTrue(committed.committed)
        assertEquals(1f, committed.state.progress)
        assertTrue(committed.state.locked)

        val repeated = committed.state
            .dragBy(-1f)
            .release()

        assertFalse(repeated.committed)
        assertEquals(committed.state, repeated.state)
    }
}
