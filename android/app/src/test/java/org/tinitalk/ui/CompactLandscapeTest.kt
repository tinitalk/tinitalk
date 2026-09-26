package org.tinitalk.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactLandscapeTest {
    @Test fun landscapeTabletsUseTwoPanesWithoutPhoneDensity() {
        for ((width, height) in listOf(1280 to 800, 1024 to 768, 960 to 600)) {
            assertTrue(usesLandscapeLayout(width, height))
            assertFalse(usesCompactLandscape(width, height))
            assertFalse(usesLandscapeLayout(height, width))
        }
        assertFalse(usesLandscapeLayout(800, 800))
        assertFalse(usesLandscapeLayout(599, 360))
        assertTrue(usesLandscapeLayout(600, 360))
    }

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
