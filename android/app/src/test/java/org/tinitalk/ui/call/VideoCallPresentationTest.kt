package org.tinitalk.ui.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoCallPresentationTest {
    @Test
    fun videoControlsFollowAutoHideTapAndVideoLoss() {
        var visible = true

        visible = nextVideoControlsVisibility(
            currentVisible = visible,
            remoteVideoVisible = true,
            event = VideoControlsVisibilityEvent.AutoHideElapsed,
        )
        assertFalse(visible)

        visible = nextVideoControlsVisibility(
            currentVisible = visible,
            remoteVideoVisible = true,
            event = VideoControlsVisibilityEvent.SurfaceTapped,
        )
        assertTrue(visible)

        visible = nextVideoControlsVisibility(
            currentVisible = visible,
            remoteVideoVisible = true,
            event = VideoControlsVisibilityEvent.SurfaceTapped,
        )
        assertFalse(visible)

        visible = nextVideoControlsVisibility(
            currentVisible = visible,
            remoteVideoVisible = false,
            event = VideoControlsVisibilityEvent.VideoChanged,
        )
        assertTrue(visible)
    }

    @Test
    fun audioOnlyCallKeepsThreeActionsAndNeverShowsCameraVideo() {
        val presentation = videoCallPresentation(
            videoAllowed = false,
            cameraRequested = true,
            localFrameVisible = true,
            remoteFrameVisible = true,
        )

        assertFalse(presentation.cameraActionVisible)
        assertFalse(presentation.localVideoVisible)
        assertFalse(presentation.remoteVideoVisible)
        assertFalse(presentation.blockProximity)
        assertEquals(3, presentation.actionCount)
        assertEquals(2, callControlColumns(videoAllowed = false, widthDp = 280f, fontScale = 2f))
    }

    @Test
    fun onlyRemoteVideoCanHideControls() {
        val local = videoCallPresentation(
            videoAllowed = true,
            cameraRequested = true,
            localFrameVisible = true,
            remoteFrameVisible = false,
        )
        val remote = videoCallPresentation(
            videoAllowed = true,
            cameraRequested = false,
            localFrameVisible = false,
            remoteFrameVisible = true,
        )

        assertTrue(local.cameraActionVisible)
        assertTrue(local.localVideoVisible)
        assertTrue(local.switchCameraEnabled)
        assertTrue(local.blockProximity)
        assertFalse(local.controlsMayAutoHide)
        assertTrue(remote.remoteVideoVisible)
        assertFalse(remote.switchCameraEnabled)
        assertTrue(remote.blockProximity)
        assertTrue(remote.controlsMayAutoHide)
    }

    @Test
    fun weakNetworkCopyRequiresAllowedRequestedAndGatedVideo() {
        assertEquals(
            "Видео временно приостановлено — слабая связь",
            weakNetworkVideoMessage(videoAllowed = true, cameraRequested = true, networkGated = true),
        )
        assertEquals(
            null,
            weakNetworkVideoMessage(videoAllowed = true, cameraRequested = false, networkGated = true),
        )
        assertEquals(
            null,
            weakNetworkVideoMessage(videoAllowed = false, cameraRequested = true, networkGated = true),
        )
        assertEquals(
            null,
            weakNetworkVideoMessage(videoAllowed = true, cameraRequested = true, networkGated = false),
        )
    }

    @Test
    fun videoControlsUseOneRowOnlyWhenWidthAndFontAllowIt() {
        assertEquals(4, callControlColumns(videoAllowed = true, widthDp = 420f, fontScale = 1f))
        assertEquals(2, callControlColumns(videoAllowed = true, widthDp = 359f, fontScale = 1f))
        assertEquals(2, callControlColumns(videoAllowed = true, widthDp = 420f, fontScale = 1.3f))
        assertEquals(3, callControlColumns(videoAllowed = false, widthDp = 320f, fontScale = 1f))
        assertTrue(CompactCallActionSizeDp >= 48)
    }

    @Test
    fun shortLargeTextLayoutsKeepEveryEssentialActionReachable() {
        val video = callControlLayout(
            videoAllowed = true,
            widthDp = 320f,
            heightDp = 260f,
            fontScale = 1.8f,
        )
        val audio = callControlLayout(
            videoAllowed = false,
            widthDp = 280f,
            heightDp = 260f,
            fontScale = 1.8f,
        )

        assertEquals(
            setOf(CallControlAction.Mute, CallControlAction.AudioRoute, CallControlAction.Camera, CallControlAction.End),
            video.actions.toSet(),
        )
        assertEquals(
            setOf(CallControlAction.Mute, CallControlAction.AudioRoute, CallControlAction.End),
            audio.actions.toSet(),
        )
        assertTrue(video.scrollable)
        assertTrue(audio.scrollable)
        assertTrue(video.viewportHeightDp >= CompactCallActionSizeDp)
        assertTrue(audio.viewportHeightDp >= CompactCallActionSizeDp)
    }
}
