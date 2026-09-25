package org.tinitalk.ui.call

import org.tinitalk.i18n.appString

import org.tinitalk.R

import kotlin.math.roundToInt

internal const val CompactCallActionSizeDp = 64
internal const val DenseVideoCallActionSizeDp = 56

internal data class SelfPreviewSize(
    val widthDp: Float,
    val heightDp: Float,
)

internal data class StableVideoSurfaceSize(
    val width: Int,
    val height: Int,
)

internal data class FittedVideoSize(val width: Float, val height: Float)

/** The Surface itself must have the frame's aspect ratio, not the enclosing screen's. */
internal fun fittedVideoSize(viewWidth: Float, viewHeight: Float, frameWidth: Int, frameHeight: Int): FittedVideoSize {
    if (frameWidth <= 0 || frameHeight <= 0) return FittedVideoSize(viewWidth, viewHeight)
    val scale = minOf(viewWidth / frameWidth, viewHeight / frameHeight)
    return FittedVideoSize(frameWidth * scale, frameHeight * scale)
}

/** Camera video fills matching orientations; unlike a shared screen, its edges may be cropped. */
internal fun cameraVideoSize(viewWidth: Float, viewHeight: Float, frameWidth: Int, frameHeight: Int): FittedVideoSize {
    val sameOrientation = frameWidth > 0 && frameHeight > 0 &&
        ((viewWidth > viewHeight && frameWidth > frameHeight) ||
            (viewWidth < viewHeight && frameWidth < frameHeight))
    // EglRenderer center-crops without stretching when its viewport differs from the frame ratio.
    return if (sameOrientation) FittedVideoSize(viewWidth, viewHeight)
        else fittedVideoSize(viewWidth, viewHeight, frameWidth, frameHeight)
}

internal fun stableVideoSurfaceSize(
    viewWidth: Int,
    viewHeight: Int,
): StableVideoSurfaceSize? {
    if (viewWidth <= 0 || viewHeight <= 0) return null
    val longestEdge = maxOf(viewWidth, viewHeight)
    if (longestEdge <= StableVideoSurfaceMaxEdgePx) {
        return StableVideoSurfaceSize(width = viewWidth, height = viewHeight)
    }
    val scale = StableVideoSurfaceMaxEdgePx.toFloat() / longestEdge
    return StableVideoSurfaceSize(
        width = (viewWidth * scale).roundToInt().coerceAtLeast(1),
        height = (viewHeight * scale).roundToInt().coerceAtLeast(1),
    )
}

internal fun selfPreviewSize(
    compact: Boolean,
    frameWidth: Int = 0,
    frameHeight: Int = 0,
    landscape: Boolean = false,
    maxWidthDp: Float = Float.POSITIVE_INFINITY,
    maxHeightDp: Float = Float.POSITIVE_INFINITY,
): SelfPreviewSize {
    val shortEdge = if (compact) 84f else 96f
    val aspect = if (frameWidth > 0 && frameHeight > 0) frameWidth.toFloat() / frameHeight
        else if (landscape) 16f / 9f else 9f / 16f
    val longEdge = shortEdge * 16f / 9f
    val width = if (aspect >= 1f) longEdge else longEdge * aspect
    val height = width / aspect
    val scale = minOf(1f, maxWidthDp.coerceAtLeast(1f) / width, maxHeightDp.coerceAtLeast(1f) / height)
    return SelfPreviewSize(width * scale, height * scale)
}

internal enum class SelfPreviewCorner {
    TopLeft,
    TopRight,
    BottomLeft,
    BottomRight,
}

internal fun storedSelfPreviewCorner(value: String?): SelfPreviewCorner =
    SelfPreviewCorner.entries.firstOrNull { it.name == value }
        ?: SelfPreviewCorner.BottomRight

internal data class SelfPreviewPosition(
    val x: Float,
    val y: Float,
)

internal data class SelfPreviewBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

internal fun selfPreviewBounds(
    containerWidth: Float,
    containerHeight: Float,
    previewWidth: Float,
    previewHeight: Float,
    safeLeft: Float,
    safeTop: Float,
    safeRight: Float,
    safeBottom: Float,
    topControlsHeight: Float,
    bottomControlsHeight: Float,
    controlsVisible: Boolean,
    edgeSpacing: Float,
): SelfPreviewBounds {
    val left = safeLeft + edgeSpacing
    val right = (containerWidth - safeRight - edgeSpacing - previewWidth).coerceAtLeast(left)
    val topClearance = if (controlsVisible) maxOf(safeTop, topControlsHeight) else safeTop
    val bottomClearance = if (controlsVisible) maxOf(safeBottom, bottomControlsHeight) else safeBottom
    val top = topClearance + edgeSpacing
    val bottom = (containerHeight - bottomClearance - edgeSpacing - previewHeight).coerceAtLeast(top)
    return SelfPreviewBounds(left = left, top = top, right = right, bottom = bottom)
}

internal fun selfPreviewPosition(
    corner: SelfPreviewCorner,
    bounds: SelfPreviewBounds,
): SelfPreviewPosition = when (corner) {
    SelfPreviewCorner.TopLeft -> SelfPreviewPosition(bounds.left, bounds.top)
    SelfPreviewCorner.TopRight -> SelfPreviewPosition(bounds.right, bounds.top)
    SelfPreviewCorner.BottomLeft -> SelfPreviewPosition(bounds.left, bounds.bottom)
    SelfPreviewCorner.BottomRight -> SelfPreviewPosition(bounds.right, bounds.bottom)
}

internal fun clampSelfPreviewPosition(
    position: SelfPreviewPosition,
    bounds: SelfPreviewBounds,
): SelfPreviewPosition = SelfPreviewPosition(
    x = position.x.coerceIn(bounds.left, bounds.right),
    y = position.y.coerceIn(bounds.top, bounds.bottom),
)

internal fun nearestSelfPreviewCorner(
    position: SelfPreviewPosition,
    bounds: SelfPreviewBounds,
): SelfPreviewCorner = SelfPreviewCorner.entries.minBy { corner ->
    val target = selfPreviewPosition(corner, bounds)
    val deltaX = position.x - target.x
    val deltaY = position.y - target.y
    deltaX * deltaX + deltaY * deltaY
}

internal enum class VideoControlsVisibilityEvent {
    VideoChanged,
    AutoHideElapsed,
    SurfaceTapped,
}

internal fun nextVideoControlsVisibility(
    currentVisible: Boolean,
    remoteVideoVisible: Boolean,
    event: VideoControlsVisibilityEvent,
): Boolean = when {
    !remoteVideoVisible -> true
    event == VideoControlsVisibilityEvent.AutoHideElapsed -> false
    event == VideoControlsVisibilityEvent.SurfaceTapped -> !currentVisible
    else -> currentVisible
}

internal enum class CallControlAction {
    SwitchCamera,
    Mute,
    AudioRoute,
    Camera,
    End,
}

internal data class CallControlLayout(
    val columns: Int,
    val actions: List<CallControlAction>,
    val buttonSizeDp: Int,
    val scrollable: Boolean,
    val viewportHeightDp: Int,
)

internal data class VideoCallPresentation(
    val cameraActionVisible: Boolean,
    val localVideoVisible: Boolean,
    val remoteVideoVisible: Boolean,
    val controlsMayAutoHide: Boolean,
    val switchCameraEnabled: Boolean,
    val blockProximity: Boolean,
    val actionCount: Int,
)

internal fun videoRecoveryOverlayVisible(
    remoteSending: Boolean,
    remoteVideoWasVisible: Boolean,
    remoteFrameVisible: Boolean,
): Boolean = remoteSending && remoteVideoWasVisible && !remoteFrameVisible

internal fun videoModeActive(
    videoAllowed: Boolean,
    localSending: Boolean,
    remoteSending: Boolean,
): Boolean = videoAllowed && (localSending || remoteSending)

internal fun videoCallPresentation(
    videoAllowed: Boolean,
    cameraRequested: Boolean,
    localFrameVisible: Boolean,
    remoteFrameVisible: Boolean,
): VideoCallPresentation {
    val localVisible = videoAllowed && cameraRequested && localFrameVisible
    val remoteVisible = videoAllowed && remoteFrameVisible
    return VideoCallPresentation(
        cameraActionVisible = videoAllowed,
        localVideoVisible = localVisible,
        remoteVideoVisible = remoteVisible,
        controlsMayAutoHide = remoteVisible,
        switchCameraEnabled = localVisible,
        blockProximity = localVisible || remoteVisible,
        actionCount = if (videoAllowed) 5 else 3,
    )
}

internal fun weakNetworkVideoMessage(
    videoAllowed: Boolean,
    cameraRequested: Boolean,
    networkGated: Boolean,
): String? = if (videoAllowed && cameraRequested && networkGated) {
    appString(R.string.text_video_paused_weak_connection_202)
} else {
    null
}

internal fun callControlColumns(
    videoAllowed: Boolean,
    videoModeActive: Boolean = false,
    cameraActionVisible: Boolean = videoAllowed,
): Int = when {
    videoModeActive -> 5
    cameraActionVisible -> 4
    else -> 3
}

internal fun callControlLayout(
    videoAllowed: Boolean,
    videoModeActive: Boolean,
    widthDp: Float,
    heightDp: Float,
    fontScale: Float,
    cameraActionVisible: Boolean = videoAllowed,
): CallControlLayout {
    val actions = buildList {
        if (videoModeActive) {
            add(CallControlAction.SwitchCamera)
            add(CallControlAction.Camera)
            add(CallControlAction.AudioRoute)
            add(CallControlAction.Mute)
            add(CallControlAction.End)
        } else {
            if (cameraActionVisible) {
                add(CallControlAction.Camera)
            }
            add(CallControlAction.AudioRoute)
            add(CallControlAction.Mute)
            add(CallControlAction.End)
        }
    }
    val columns = callControlColumns(
        videoAllowed = videoAllowed,
        videoModeActive = videoModeActive,
        cameraActionVisible = cameraActionVisible,
    )
    return CallControlLayout(
        columns = columns,
        actions = actions,
        buttonSizeDp = if (videoModeActive && widthDp < CompactCallActionSizeDp * 5) {
            DenseVideoCallActionSizeDp
        } else {
            CompactCallActionSizeDp
        },
        scrollable = heightDp < 560f || fontScale >= 1.3f,
        viewportHeightDp = (heightDp - 150f)
            .coerceAtLeast(CompactCallActionSizeDp.toFloat())
            .coerceAtMost(320f)
            .coerceAtMost(heightDp.coerceAtLeast(CompactCallActionSizeDp.toFloat()))
            .toInt(),
    )
}

private const val StableVideoSurfaceMaxEdgePx = 1920
