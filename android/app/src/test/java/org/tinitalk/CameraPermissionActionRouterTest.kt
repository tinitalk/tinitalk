package org.tinitalk

import org.tinitalk.call.CallDirection
import org.tinitalk.call.CallPhase
import org.tinitalk.call.CallUiState
import org.tinitalk.call.CallVideoState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraPermissionActionRouterTest {
    @Test
    fun denialAfterActiveCallActionDoesNotReachCallMedia() {
        var permissionRequests = 0
        val enabledCalls = mutableListOf<String>()
        val router = CameraPermissionActionRouter(
            permissionGranted = { false },
            requestPermission = { permissionRequests++ },
            enableCamera = enabledCalls::add,
        )

        router.request(activeCall(CurrentCall), videoState(CurrentCall))
        router.onPermissionResult(false, activeCall(CurrentCall), videoState(CurrentCall))

        assertEquals(1, permissionRequests)
        assertTrue(enabledCalls.isEmpty())
    }

    @Test
    fun stalePermissionGrantCannotEnableCameraForAReplacementCall() {
        val enabledCalls = mutableListOf<String>()
        val router = CameraPermissionActionRouter(
            permissionGranted = { false },
            requestPermission = {},
            enableCamera = enabledCalls::add,
        )

        router.request(activeCall(CurrentCall), videoState(CurrentCall))
        router.onPermissionResult(true, activeCall(ReplacementCall), videoState(ReplacementCall))

        assertTrue(enabledCalls.isEmpty())
    }

    @Test
    fun existingPermissionRoutesOnlyAnAllowedActiveCall() {
        val enabledCalls = mutableListOf<String>()
        val router = CameraPermissionActionRouter(
            permissionGranted = { true },
            requestPermission = {},
            enableCamera = enabledCalls::add,
        )

        router.request(activeCall(CurrentCall), videoState(CurrentCall))
        router.request(activeCall(ReplacementCall), videoState(ReplacementCall, allowed = false))
        router.request(activeCall("ringing", CallPhase.Ringing), videoState("ringing"))

        assertEquals(listOf(CurrentCall), enabledCalls)
    }

    @Test
    fun pendingPermissionCallSurvivesRecreationAndIsRevalidated() {
        var permissionGranted = false
        val first = CameraPermissionActionRouter(
            permissionGranted = { permissionGranted },
            requestPermission = {},
            enableCamera = {},
            cameraVisibleAndUnlocked = { true },
        )
        first.request(activeCall(CurrentCall), videoState(CurrentCall))
        val savedCallId = first.pendingCallId()
        val enabledCalls = mutableListOf<String>()
        permissionGranted = true
        val recreated = CameraPermissionActionRouter(
            permissionGranted = { permissionGranted },
            requestPermission = {},
            enableCamera = enabledCalls::add,
            cameraVisibleAndUnlocked = { true },
            restoredPendingCallId = savedCallId,
        )

        recreated.onPermissionResult(true, activeCall(CurrentCall), videoState(CurrentCall))

        assertEquals(listOf(CurrentCall), enabledCalls)
    }

    @Test
    fun recreatedPermissionResultIsIgnoredWhileLockedOrNotVisible() {
        val enabledCalls = mutableListOf<String>()
        var visible = false
        val recreated = CameraPermissionActionRouter(
            permissionGranted = { true },
            requestPermission = {},
            enableCamera = enabledCalls::add,
            cameraVisibleAndUnlocked = { visible },
            restoredPendingCallId = CurrentCall,
        )

        recreated.onPermissionResult(true, activeCall(CurrentCall), videoState(CurrentCall))
        assertTrue(enabledCalls.isEmpty())

        visible = true
        recreated.onVisible(activeCall(CurrentCall), videoState(CurrentCall))

        assertEquals(listOf(CurrentCall), enabledCalls)
    }

    private fun activeCall(callId: String, phase: CallPhase = CallPhase.Active) = CallUiState(
        callId = callId,
        direction = CallDirection.Outgoing,
        phase = phase,
    )

    private fun videoState(callId: String, allowed: Boolean = true) =
        CallVideoState<Nothing>(callId = callId, allowed = allowed)

    private companion object {
        const val CurrentCall = "call-1"
        const val ReplacementCall = "call-2"
    }
}
