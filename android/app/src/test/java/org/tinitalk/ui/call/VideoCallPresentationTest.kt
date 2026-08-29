package org.tinitalk.ui.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoCallPresentationTest {
    @Test
    fun videoRecoveryOverlayRequiresAnInterruptedRemoteVideoRun() {
        assertTrue(
            videoRecoveryOverlayVisible(
                remoteSending = true,
                remoteVideoWasVisible = true,
                remoteFrameVisible = false,
            ),
        )
        assertFalse(
            videoRecoveryOverlayVisible(
                remoteSending = true,
                remoteVideoWasVisible = false,
                remoteFrameVisible = false,
            ),
        )
        assertFalse(
            videoRecoveryOverlayVisible(
                remoteSending = false,
                remoteVideoWasVisible = true,
                remoteFrameVisible = false,
            ),
        )
        assertFalse(
            videoRecoveryOverlayVisible(
                remoteSending = true,
                remoteVideoWasVisible = true,
                remoteFrameVisible = true,
            ),
        )
    }

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
        assertEquals(3, callControlColumns(videoAllowed = false))
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
    fun videoModeRequiresActualLocalOrRemoteSending() {
        assertFalse(videoModeActive(videoAllowed = false, localSending = true, remoteSending = true))
        assertFalse(videoModeActive(videoAllowed = true, localSending = false, remoteSending = false))
        assertTrue(videoModeActive(videoAllowed = true, localSending = true, remoteSending = false))
        assertTrue(videoModeActive(videoAllowed = true, localSending = false, remoteSending = true))
    }

    @Test
    fun selfiePreviewMatchesPortraitOutgoingVideoAspectRatio() {
        val regular = selfPreviewSize(compact = false)
        val compact = selfPreviewSize(compact = true)

        assertEquals(96f, regular.widthDp, 0f)
        assertEquals(84f, compact.widthDp, 0f)
        assertEquals(9f / 16f, regular.widthDp / regular.heightDp, 0.0001f)
        assertEquals(9f / 16f, compact.widthDp / compact.heightDp, 0.0001f)
    }

    @Test
    fun largeVideoSurfaceKeepsScreenAspectRatioAt1080pLimit() {
        assertEquals(
            StableVideoSurfaceSize(width = 864, height = 1920),
            stableVideoSurfaceSize(viewWidth = 1440, viewHeight = 3200),
        )
        assertEquals(
            StableVideoSurfaceSize(width = 1920, height = 864),
            stableVideoSurfaceSize(viewWidth = 3200, viewHeight = 1440),
        )
    }

    @Test
    fun smallVideoSurfaceKeepsActualScreenPixels() {
        assertEquals(
            StableVideoSurfaceSize(width = 720, height = 1600),
            stableVideoSurfaceSize(viewWidth = 720, viewHeight = 1600),
        )
    }

    @Test
    fun selfiePreviewRestoresSavedCorner() {
        assertEquals(
            SelfPreviewCorner.TopLeft,
            storedSelfPreviewCorner("TopLeft"),
        )
    }

    @Test
    fun selfiePreviewDefaultsToBottomRightForMissingOrUnknownCorner() {
        assertEquals(SelfPreviewCorner.BottomRight, storedSelfPreviewCorner(null))
        assertEquals(SelfPreviewCorner.BottomRight, storedSelfPreviewCorner("unknown"))
    }

    @Test
    fun selfiePreviewStaysInsideSafeAreaAndSnapsToNearestCorner() {
        val visibleBounds = selfPreviewBounds(
            containerWidth = 360f,
            containerHeight = 720f,
            previewWidth = 96f,
            previewHeight = 160f,
            safeLeft = 0f,
            safeTop = 24f,
            safeRight = 0f,
            safeBottom = 20f,
            topControlsHeight = 112f,
            bottomControlsHeight = 180f,
            controlsVisible = true,
            edgeSpacing = 12f,
        )
        val hiddenBounds = selfPreviewBounds(
            containerWidth = 360f,
            containerHeight = 720f,
            previewWidth = 96f,
            previewHeight = 160f,
            safeLeft = 0f,
            safeTop = 24f,
            safeRight = 0f,
            safeBottom = 20f,
            topControlsHeight = 112f,
            bottomControlsHeight = 180f,
            controlsVisible = false,
            edgeSpacing = 12f,
        )

        assertEquals(SelfPreviewBounds(12f, 124f, 252f, 368f), visibleBounds)
        assertEquals(SelfPreviewBounds(12f, 36f, 252f, 528f), hiddenBounds)
        assertEquals(
            SelfPreviewPosition(12f, 368f),
            clampSelfPreviewPosition(SelfPreviewPosition(-50f, 999f), visibleBounds),
        )
        assertEquals(
            SelfPreviewCorner.BottomRight,
            nearestSelfPreviewCorner(SelfPreviewPosition(240f, 350f), visibleBounds),
        )
        assertEquals(
            SelfPreviewPosition(252f, 368f),
            selfPreviewPosition(SelfPreviewCorner.BottomRight, visibleBounds),
        )
    }

    @Test
    fun shortLargeTextLayoutsKeepEveryEssentialActionReachable() {
        val video = callControlLayout(
            videoAllowed = true,
            videoModeActive = true,
            widthDp = 320f,
            heightDp = 260f,
            fontScale = 1.8f,
        )
        val cameraReady = callControlLayout(
            videoAllowed = true,
            videoModeActive = false,
            widthDp = 320f,
            heightDp = 260f,
            fontScale = 1.8f,
        )
        val audio = callControlLayout(
            videoAllowed = false,
            videoModeActive = false,
            widthDp = 280f,
            heightDp = 260f,
            fontScale = 1.8f,
        )

        assertEquals(
            listOf(
                CallControlAction.SwitchCamera,
                CallControlAction.Camera,
                CallControlAction.AudioRoute,
                CallControlAction.Mute,
                CallControlAction.End,
            ),
            video.actions,
        )
        assertEquals(
            listOf(
                CallControlAction.Camera,
                CallControlAction.AudioRoute,
                CallControlAction.Mute,
                CallControlAction.End,
            ),
            cameraReady.actions,
        )
        assertEquals(
            listOf(CallControlAction.AudioRoute, CallControlAction.Mute, CallControlAction.End),
            audio.actions,
        )
        assertEquals(5, video.columns)
        assertEquals(4, cameraReady.columns)
        assertEquals(3, audio.columns)
        assertTrue(video.scrollable)
        assertTrue(cameraReady.scrollable)
        assertTrue(audio.scrollable)
        assertTrue(video.viewportHeightDp >= CompactCallActionSizeDp)
        assertTrue(audio.viewportHeightDp >= CompactCallActionSizeDp)
    }
}
