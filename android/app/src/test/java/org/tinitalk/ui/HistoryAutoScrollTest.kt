package org.tinitalk.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryAutoScrollTest {
    @Test
    fun `scrolls only when an already shown history receives a new first entry`() {
        assertFalse(shouldScrollToNewest(previousFirstId = null, currentFirstId = 10))
        assertFalse(shouldScrollToNewest(previousFirstId = 10, currentFirstId = 10))
        assertFalse(shouldScrollToNewest(previousFirstId = 10, currentFirstId = null))
        assertTrue(shouldScrollToNewest(previousFirstId = 10, currentFirstId = 11))
    }
}
