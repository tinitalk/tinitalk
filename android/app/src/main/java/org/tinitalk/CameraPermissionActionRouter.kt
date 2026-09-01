package org.tinitalk

import org.tinitalk.call.CallPhase
import org.tinitalk.call.AccountCallKey
import org.tinitalk.call.CallUiState
import org.tinitalk.call.CallVideoState

internal class CameraPermissionActionRouter(
    private val permissionGranted: () -> Boolean,
    private val requestPermission: () -> Unit,
    private val enableCamera: (AccountCallKey) -> Unit,
    private val cameraVisible: () -> Boolean = { true },
    restoredPendingCallKey: AccountCallKey? = null,
) {
    private var pendingCallKey: AccountCallKey? = restoredPendingCallKey

    fun pendingCallKey(): AccountCallKey? = pendingCallKey

    fun request(call: CallUiState, video: CallVideoState<*>) {
        val callKey = eligibleCallKey(call, video) ?: return
        if (permissionGranted()) {
            pendingCallKey = callKey
            enablePendingIfEligible(call, video)
        } else {
            pendingCallKey = callKey
            requestPermission()
        }
    }

    fun onPermissionResult(granted: Boolean, call: CallUiState, video: CallVideoState<*>) {
        val requestedCallKey = pendingCallKey ?: return
        if (!granted || !permissionGranted() || eligibleCallKey(call, video) != requestedCallKey) {
            pendingCallKey = null
            return
        }
        enablePendingIfEligible(call, video)
    }

    fun onVisible(call: CallUiState, video: CallVideoState<*>) = enablePendingIfEligible(call, video)

    private fun enablePendingIfEligible(call: CallUiState, video: CallVideoState<*>) {
        val requestedCallKey = pendingCallKey ?: return
        if (!permissionGranted() || !cameraVisible()) return
        if (eligibleCallKey(call, video) != requestedCallKey) {
            pendingCallKey = null
            return
        }
        pendingCallKey = null
        enableCamera(requestedCallKey)
    }

    private fun eligibleCallKey(call: CallUiState, video: CallVideoState<*>): AccountCallKey? =
        call.callKey?.takeIf { call.phase == CallPhase.Active && video.callKey == it && video.allowed }
}
