package org.tinitalk.ui.call

internal const val CompactCallActionSizeDp = 64

internal data class SelfPreviewSize(
    val widthDp: Float,
    val heightDp: Float,
)

internal fun selfPreviewSize(compact: Boolean): SelfPreviewSize {
    val widthDp = if (compact) 84f else 96f
    return SelfPreviewSize(widthDp = widthDp, heightDp = widthDp * 16f / 9f)
}

internal enum class SelfPreviewCorner {
    TopLeft,
    TopRight,
    BottomLeft,
    BottomRight,
}

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
        actionCount = if (videoAllowed) 4 else 3,
    )
}

internal fun weakNetworkVideoMessage(
    videoAllowed: Boolean,
    cameraRequested: Boolean,
    networkGated: Boolean,
): String? = if (videoAllowed && cameraRequested && networkGated) {
    "Видео временно приостановлено — слабая связь"
} else {
    null
}

internal fun callControlColumns(videoAllowed: Boolean): Int = if (videoAllowed) 4 else 3

internal fun callControlLayout(
    videoAllowed: Boolean,
    videoModeActive: Boolean,
    widthDp: Float,
    heightDp: Float,
    fontScale: Float,
): CallControlLayout {
    val actions = buildList {
        if (videoModeActive) {
            add(CallControlAction.SwitchCamera)
            add(CallControlAction.Camera)
            add(CallControlAction.Mute)
            add(CallControlAction.End)
        } else if (videoAllowed) {
            add(CallControlAction.AudioRoute)
            add(CallControlAction.Camera)
            add(CallControlAction.Mute)
            add(CallControlAction.End)
        } else {
            add(CallControlAction.Mute)
            add(CallControlAction.AudioRoute)
            add(CallControlAction.End)
        }
    }
    val viewportHeight = (heightDp - 150f)
        .coerceAtLeast(CompactCallActionSizeDp.toFloat())
        .coerceAtMost(320f)
        .coerceAtMost(heightDp.coerceAtLeast(CompactCallActionSizeDp.toFloat()))
        .toInt()
    return CallControlLayout(
        columns = callControlColumns(videoAllowed),
        actions = actions,
        scrollable = heightDp < 560f || fontScale >= 1.3f,
        viewportHeightDp = viewportHeight,
    )
}
