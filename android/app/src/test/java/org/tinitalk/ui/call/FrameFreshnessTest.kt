package org.tinitalk.ui.call

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class FrameFreshnessTest {
    @Test
    fun frameExpiresAfterExactlyFifteenHundredMillisecondsAndCanReturn() {
        val freshness = FrameFreshness(timeoutMillis = 1_500)

        assertEquals(FrameVisibilityChange.BecameVisible, freshness.onFrame(10_000))
        assertEquals(FrameVisibilityChange.None, freshness.onTimeout(11_499))
        assertEquals(FrameVisibilityChange.BecameHidden, freshness.onTimeout(11_500))
        assertEquals(FrameVisibilityChange.None, freshness.onTimeout(11_500))

        assertEquals(FrameVisibilityChange.BecameVisible, freshness.onFrame(12_000))
        assertEquals(1_500L, freshness.remainingMillis(12_000))
    }

    @Test
    fun closedSourceIgnoresLateDecoderFrames() {
        val freshness = FrameFreshness(timeoutMillis = 1_500)
        freshness.onFrame(100)

        freshness.close()

        assertEquals(FrameVisibilityChange.None, freshness.onFrame(200))
        assertEquals(FrameVisibilityChange.None, freshness.onTimeout(2_000))
        assertFalse(freshness.visible)
    }

    @Test
    fun replacementSourceIsIndependentFromLateOldSourceTimeout() {
        val oldSource = FrameFreshness(timeoutMillis = 1_500)
        oldSource.onFrame(100)
        oldSource.close()
        val replacement = FrameFreshness(timeoutMillis = 1_500)

        assertEquals(FrameVisibilityChange.BecameVisible, replacement.onFrame(1_000))
        assertEquals(FrameVisibilityChange.None, oldSource.onTimeout(10_000))
        assertTrue(replacement.visible)
    }
}
