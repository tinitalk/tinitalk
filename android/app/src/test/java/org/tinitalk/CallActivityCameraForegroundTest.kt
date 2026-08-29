package org.tinitalk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CallActivityCameraForegroundTest {
    @Test
    fun visibleLockscreenCallPausesOnlyWhileScreenIsOffOrActivityIsBackgrounded() {
        val foregroundStates = mutableListOf<Boolean>()
        var pendingCameraRetries = 0
        var screenInteractive = true
        val lifecycle = CallActivityCameraForeground(
            screenInteractive = { screenInteractive },
            retryPendingCamera = { pendingCameraRetries++ },
            publish = { foregroundStates += it },
        )

        lifecycle.onResume()
        screenInteractive = false
        assertFalse(lifecycle.visible)
        lifecycle.onScreenOff()
        screenInteractive = true
        lifecycle.onScreenOn()
        lifecycle.onPause()
        lifecycle.onScreenOn()

        assertEquals(listOf(true, false, true, false), foregroundStates)
        assertEquals(2, pendingCameraRetries)
    }
}
