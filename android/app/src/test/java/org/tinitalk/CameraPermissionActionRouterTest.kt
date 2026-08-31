package org.tinitalk

import org.tinitalk.call.CallDirection
import org.tinitalk.call.AccountCallKey
import org.tinitalk.call.CallPhase
import org.tinitalk.call.CallUiState
import org.tinitalk.call.CallVideoState
import org.tinitalk.data.AccountId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraPermissionActionRouterTest {
    @Test
    fun denialAfterActiveCallActionDoesNotReachCallMedia() {
        var permissionRequests = 0
        val enabledCalls = mutableListOf<AccountCallKey>()
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
        val enabledCalls = mutableListOf<AccountCallKey>()
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
    fun permissionGrantForOneAccountCannotEnableSameCallIdOnAnotherAccount() {
        var permissionGranted = false
        val enabledCalls = mutableListOf<AccountCallKey>()
        val router = CameraPermissionActionRouter(
            permissionGranted = { permissionGranted },
            requestPermission = {},
            enableCamera = enabledCalls::add,
        )

        router.request(activeCall(AccountA, SameCall), videoState(AccountA, SameCall))
        permissionGranted = true
        router.onPermissionResult(true, activeCall(AccountB, SameCall), videoState(AccountB, SameCall))

        assertTrue(enabledCalls.isEmpty())
    }

    @Test
    fun existingPermissionRoutesOnlyAnAllowedActiveCall() {
        val enabledCalls = mutableListOf<AccountCallKey>()
        val router = CameraPermissionActionRouter(
            permissionGranted = { true },
            requestPermission = {},
            enableCamera = enabledCalls::add,
        )

        router.request(activeCall(CurrentCall), videoState(CurrentCall))
        router.request(activeCall(ReplacementCall), videoState(ReplacementCall, allowed = false))
        router.request(activeCall("ringing", CallPhase.Ringing), videoState("ringing"))

        assertEquals(listOf(AccountCallKey(AccountA, CurrentCall)), enabledCalls)
    }

    @Test
    fun pendingPermissionCallSurvivesRecreationAndIsRevalidated() {
        var permissionGranted = false
        val first = CameraPermissionActionRouter(
            permissionGranted = { permissionGranted },
            requestPermission = {},
            enableCamera = {},
            cameraVisible = { true },
        )
        first.request(activeCall(AccountA, CurrentCall), videoState(AccountA, CurrentCall))
        val savedCallKey = first.pendingCallKey()
        val enabledCalls = mutableListOf<AccountCallKey>()
        permissionGranted = true
        val recreated = CameraPermissionActionRouter(
            permissionGranted = { permissionGranted },
            requestPermission = {},
            enableCamera = enabledCalls::add,
            cameraVisible = { true },
            restoredPendingCallKey = savedCallKey,
        )

        recreated.onPermissionResult(true, activeCall(AccountA, CurrentCall), videoState(AccountA, CurrentCall))

        assertEquals(listOf(AccountCallKey(AccountA, CurrentCall)), enabledCalls)
    }

    @Test
    fun recreatedPermissionResultIsIgnoredWhileNotVisible() {
        val enabledCalls = mutableListOf<AccountCallKey>()
        var visible = false
        val recreated = CameraPermissionActionRouter(
            permissionGranted = { true },
            requestPermission = {},
            enableCamera = enabledCalls::add,
            cameraVisible = { visible },
            restoredPendingCallKey = AccountCallKey(AccountA, CurrentCall),
        )

        recreated.onPermissionResult(true, activeCall(AccountA, CurrentCall), videoState(AccountA, CurrentCall))
        assertTrue(enabledCalls.isEmpty())

        visible = true
        recreated.onVisible(activeCall(AccountA, CurrentCall), videoState(AccountA, CurrentCall))

        assertEquals(listOf(AccountCallKey(AccountA, CurrentCall)), enabledCalls)
    }

    private fun activeCall(callId: String, phase: CallPhase = CallPhase.Active) =
        activeCall(AccountA, callId, phase)

    private fun activeCall(
        accountId: AccountId,
        callId: String,
        phase: CallPhase = CallPhase.Active,
    ) = CallUiState(
        accountId = accountId,
        callId = callId,
        direction = CallDirection.Outgoing,
        phase = phase,
    )

    private fun videoState(callId: String, allowed: Boolean = true) =
        videoState(AccountA, callId, allowed)

    private fun videoState(
        accountId: AccountId,
        callId: String,
        allowed: Boolean = true,
    ) = CallVideoState<Nothing>(callId = callId, allowed = allowed, accountId = accountId)

    private companion object {
        const val CurrentCall = "call-1"
        const val ReplacementCall = "call-2"
        const val SameCall = "same-call"
        val AccountA = AccountId("account-a")
        val AccountB = AccountId("account-b")
    }
}
