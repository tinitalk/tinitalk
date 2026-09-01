package org.tinitalk.call

import org.tinitalk.data.AccountId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallVideoStateTest {
    @Test
    fun matchingAllowedRemoteVideoEventUpdatesTransmissionState() {
        val configured = CallVideoState<String>(callId = CurrentCall, allowed = true)

        val enabled = configured.withRemoteSending(CurrentCall, enabled = true)
        val disabled = enabled.withRemoteSending(CurrentCall, enabled = false)

        assertTrue(enabled.remoteSending)
        assertFalse(disabled.remoteSending)
    }

    @Test
    fun staleOrDisallowedRemoteVideoEventsCannotChangeTransmissionState() {
        val configured = CallVideoState<String>(callId = CurrentCall, allowed = true)
        val disallowed = CallVideoState<String>(callId = CurrentCall, allowed = false)

        assertFalse(configured.withRemoteSending(ReplacementCall, enabled = true).remoteSending)
        assertFalse(disallowed.withRemoteSending(CurrentCall, enabled = true).remoteSending)
    }

    @Test
    fun replacementCallConfigurationClearsRemoteTransmissionState() {
        val receiving = CallVideoState<String>(callId = CurrentCall, allowed = true)
            .withRemoteSending(CurrentCall, enabled = true)

        val replacement = receiving.configured(AccountA, ReplacementCall, videoAllowed = true)

        assertFalse(replacement.remoteSending)
    }

    @Test
    fun configuredVideoStateCarriesAccountCallKey() {
        val state = CallVideoState<String>().configured(AccountA, SameCall, videoAllowed = true)

        assertEquals(AccountCallKey(AccountA, SameCall), state.callKey)
    }

    @Test
    fun backgroundPauseKeepsRequestedIntentAndLocalTrackForResume() {
        val active = CallVideoState<String>(callId = CurrentCall, allowed = true)
            .request(CurrentCall, permissionGranted = true)
            .withLocalTrack(CurrentCall, "local-track")
            .captureStarted(CurrentCall, CameraFacing.Front)

        val paused = active.captureStopped(CurrentCall)

        assertTrue(paused.requested)
        assertTrue(paused.permissionGranted)
        assertFalse(paused.sending)
        assertEquals("local-track", paused.localTrack)
        assertTrue(paused.captureStarted(CurrentCall, CameraFacing.Front).sending)
    }

    @Test
    fun manualOffClearsRequestedIntentAndLocalTrack() {
        val active = CallVideoState<String>(callId = CurrentCall, allowed = true)
            .request(CurrentCall, permissionGranted = true)
            .withLocalTrack(CurrentCall, "local-track")
            .captureStarted(CurrentCall, CameraFacing.Front)

        val stopped = active.manualOff(CurrentCall)

        assertFalse(stopped.requested)
        assertFalse(stopped.sending)
        assertNull(stopped.localTrack)
        assertEquals(CameraFacing.Front, stopped.facing)
    }

    @Test
    fun staleTrackAndCaptureCallbacksCannotChangeReplacementCall() {
        val replacement = CallVideoState<String>(callId = ReplacementCall, allowed = true)
            .request(ReplacementCall, permissionGranted = true)

        val afterStaleCallbacks = replacement
            .withLocalTrack(CurrentCall, "stale-local")
            .withRemoteTrack(CurrentCall, "stale-remote")
            .captureStarted(CurrentCall, CameraFacing.Back)

        assertEquals(ReplacementCall, afterStaleCallbacks.callId)
        assertNull(afterStaleCallbacks.localTrack)
        assertNull(afterStaleCallbacks.remoteTrack)
        assertFalse(afterStaleCallbacks.sending)
        assertEquals(CameraFacing.Front, afterStaleCallbacks.facing)
    }

    @Test
    fun cameraFailureStopsSendingWithoutClearingRequestedIntent() {
        val active = CallVideoState<String>(callId = CurrentCall, allowed = true)
            .request(CurrentCall, permissionGranted = true)
            .withLocalTrack(CurrentCall, "local-track")
            .captureStarted(CurrentCall, CameraFacing.Front)

        val failed = active.failed(CurrentCall, "camera disconnected")

        assertTrue(failed.requested)
        assertFalse(failed.sending)
        assertNull(failed.localTrack)
        assertEquals("camera disconnected", failed.failure)
        assertEquals(CameraFacing.Front, failed.facing)
    }

    private companion object {
        const val CurrentCall = "call-1"
        const val ReplacementCall = "call-2"
        const val SameCall = "same-call"
        val AccountA = AccountId("account-a")
    }
}
