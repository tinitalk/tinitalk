package org.tinitalk

import org.tinitalk.call.AccountCallKey
import org.tinitalk.data.AccountId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraForegroundPublicationGateTest {
    @Test
    fun repeatedCameraForegroundStateIsNotPublishedAgain() {
        val gate = CameraForegroundPublicationGate()

        val callKey = AccountCallKey(AccountA, CallId)

        assertTrue(gate.shouldPublish(callKey, foreground = true, permissionGranted = false))
        assertFalse(gate.shouldPublish(callKey, foreground = true, permissionGranted = false))
        assertTrue(gate.shouldPublish(callKey, foreground = true, permissionGranted = true))
        assertTrue(gate.shouldPublish(callKey, foreground = false, permissionGranted = true))
    }

    @Test
    fun sameCallIdOnAnotherAccountRequiresForegroundPublication() {
        val gate = CameraForegroundPublicationGate()

        assertTrue(gate.shouldPublish(AccountCallKey(AccountA, SameCall), foreground = true, permissionGranted = true))
        assertTrue(gate.shouldPublish(AccountCallKey(AccountB, SameCall), foreground = true, permissionGranted = true))
    }

    private companion object {
        const val CallId = "call-camera-foreground"
        const val SameCall = "same-call"
        val AccountA = AccountId("account-a")
        val AccountB = AccountId("account-b")
    }
}
