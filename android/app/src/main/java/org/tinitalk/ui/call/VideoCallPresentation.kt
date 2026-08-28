package org.tinitalk.ui.call

internal const val CompactCallActionSizeDp = 64

internal enum class CallControlAction {
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
    val switchCameraEnabled: Boolean,
    val blockProximity: Boolean,
    val actionCount: Int,
)

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
        switchCameraEnabled = localVisible,
        blockProximity = localVisible || remoteVisible,
        actionCount = if (videoAllowed) 4 else 3,
    )
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
    widthDp: Float,
    heightDp: Float,
    fontScale: Float,
): CallControlLayout {
    val actions = buildList {
        add(CallControlAction.Mute)
        add(CallControlAction.AudioRoute)
        if (videoAllowed) add(CallControlAction.Camera)
        add(CallControlAction.End)
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
