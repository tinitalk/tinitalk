package org.tinitalk.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryAutoScrollTest {
    @Test
    fun `scrolls only when an already shown history receives a new first entry`() {
        assertFalse(shouldScrollToNewest(previousFirstKey = null, currentFirstKey = "a:10"))
        assertFalse(shouldScrollToNewest(previousFirstKey = "a:10", currentFirstKey = "a:10"))
        assertFalse(shouldScrollToNewest(previousFirstKey = "a:10", currentFirstKey = null))
        assertTrue(shouldScrollToNewest(previousFirstKey = "a:7", currentFirstKey = "b:7"))
    }
}
