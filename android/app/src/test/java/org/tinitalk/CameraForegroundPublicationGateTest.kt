package org.tinitalk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraForegroundPublicationGateTest {
    @Test
    fun repeatedCameraForegroundStateIsNotPublishedAgain() {
        val gate = CameraForegroundPublicationGate()

        assertTrue(gate.shouldPublish(CallId, foreground = true, permissionGranted = false))
        assertFalse(gate.shouldPublish(CallId, foreground = true, permissionGranted = false))
        assertTrue(gate.shouldPublish(CallId, foreground = true, permissionGranted = true))
        assertTrue(gate.shouldPublish(CallId, foreground = false, permissionGranted = true))
    }

    private companion object {
        const val CallId = "call-camera-foreground"
    }
}
