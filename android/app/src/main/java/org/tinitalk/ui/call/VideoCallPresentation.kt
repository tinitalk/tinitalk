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

internal fun callControlColumns(
    videoAllowed: Boolean,
    widthDp: Float,
    fontScale: Float,
): Int = when {
    fontScale >= 1.3f -> 2
    videoAllowed && widthDp < 360f -> 2
    !videoAllowed && widthDp < 300f -> 2
    videoAllowed -> 4
    else -> 3
}

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
        columns = callControlColumns(videoAllowed, widthDp, fontScale),
        actions = actions,
        scrollable = heightDp < 560f || fontScale >= 1.3f,
        viewportHeightDp = viewportHeight,
    )
}
