package org.tinitalk

import org.tinitalk.call.CallPhase
import org.tinitalk.call.CallUiState
import org.tinitalk.call.CallVideoState

internal class CameraPermissionActionRouter(
    private val permissionGranted: () -> Boolean,
    private val requestPermission: () -> Unit,
    private val enableCamera: (String) -> Unit,
    private val cameraVisible: () -> Boolean = { true },
    restoredPendingCallId: String? = null,
) {
    private var pendingCallId: String? = restoredPendingCallId

    fun pendingCallId(): String? = pendingCallId

    fun request(call: CallUiState, video: CallVideoState<*>) {
        val callId = eligibleCallId(call, video) ?: return
        if (permissionGranted()) {
            pendingCallId = callId
            enablePendingIfEligible(call, video)
        } else {
            pendingCallId = callId
            requestPermission()
        }
    }

    fun onPermissionResult(granted: Boolean, call: CallUiState, video: CallVideoState<*>) {
        val requestedCallId = pendingCallId ?: return
        if (!granted || !permissionGranted() || eligibleCallId(call, video) != requestedCallId) {
            pendingCallId = null
            return
        }
        enablePendingIfEligible(call, video)
    }

    fun onVisible(call: CallUiState, video: CallVideoState<*>) = enablePendingIfEligible(call, video)

    private fun enablePendingIfEligible(call: CallUiState, video: CallVideoState<*>) {
        val requestedCallId = pendingCallId ?: return
        if (!permissionGranted() || !cameraVisible()) return
        if (eligibleCallId(call, video) != requestedCallId) {
            pendingCallId = null
            return
        }
        pendingCallId = null
        enableCamera(requestedCallId)
    }

    private fun eligibleCallId(call: CallUiState, video: CallVideoState<*>): String? {
        val callId = call.callId ?: return null
        return callId.takeIf {
            call.phase == CallPhase.Active && video.callId == callId && video.allowed
        }
    }
}
