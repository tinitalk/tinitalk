package org.tinitalk.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactLandscapeTest {
    @Test fun shortWidePhonesUseTwoPanes() {
        assertTrue(usesCompactLandscape(853, 384))
        assertTrue(usesCompactLandscape(640, 360))
    }

    @Test fun portraitAndTallWindowsKeepTheirExistingLayout() {
        assertFalse(usesCompactLandscape(384, 853))
        assertFalse(usesCompactLandscape(1000, 800))
        assertFalse(usesCompactLandscape(540, 320))
    }
}
