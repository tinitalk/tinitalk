package org.tinitalk.ui.call

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.core.telecom.CallEndpointCompat
import org.tinitalk.R
import org.tinitalk.call.CallVideoState
import org.tinitalk.call.CallEndReason
import org.tinitalk.call.CameraFacing
import org.tinitalk.call.ConnectionHealth
import org.tinitalk.media.VideoRenderSource
import org.tinitalk.telecom.AudioEndpoint
import org.tinitalk.ui.contactInitial
import org.tinitalk.ui.theme.CallBackgroundBottom
import org.tinitalk.ui.theme.CallBackgroundTop
import org.tinitalk.ui.theme.CallRejectRed
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActiveCallScreen(
    peerName: String,
    durationText: String,
    muted: Boolean,
    connectionHealth: ConnectionHealth,
    currentEndpoint: AudioEndpoint?,
    availableEndpoints: List<AudioEndpoint>,
    videoState: CallVideoState<VideoRenderSource>,
    onMute: (Boolean) -> Unit,
    onSelectEndpoint: (AudioEndpoint) -> Unit,
    onCamera: (Boolean) -> Unit,
    onSwitchCamera: () -> Unit,
    onVideoVisibilityChanged: (Boolean) -> Unit,
    onEnd: () -> Unit,
) {
    var routePickerVisible by remember { mutableStateOf(false) }
    val status = when (connectionHealth) {
        ConnectionHealth.Connecting -> "Соединяемся…"
        ConnectionHealth.Reconnecting -> "Восстанавливаем связь…"
        ConnectionHealth.Poor -> "Слабая сеть"
        else -> "Идёт разговор"
    }
    val statusColor = if (connectionHealth == ConnectionHealth.Poor || connectionHealth == ConnectionHealth.Reconnecting) {
        Color(0xFFFFCA6A)
    } else {
        Color.White.copy(alpha = 0.76f)
    }
    val videoMode = videoModeActive(
        videoAllowed = videoState.allowed,
        localSending = videoState.sending,
        remoteSending = videoState.remoteSending,
    )
    val cameraPressed: (Boolean) -> Unit = { requested ->
        speakerRouteOnCameraPress(requested, currentEndpoint, availableEndpoints)?.let(onSelectEndpoint)
        onCamera(requested)
    }

    LaunchedEffect(videoState.callId, videoMode) {
        if (videoMode) {
            speakerRouteOnCameraPress(true, currentEndpoint, availableEndpoints)?.let(onSelectEndpoint)
        }
    }

    if (videoMode) {
        VideoActiveCallScreen(
            peerName = peerName,
            durationText = durationText,
            status = status,
            statusColor = statusColor,
            muted = muted,
            currentEndpoint = currentEndpoint,
            availableEndpoints = availableEndpoints,
            videoState = videoState,
            onMute = onMute,
            onSelectEndpoint = onSelectEndpoint,
            onShowRoutePicker = { routePickerVisible = true },
            onCamera = cameraPressed,
            onSwitchCamera = onSwitchCamera,
            onVideoVisibilityChanged = onVideoVisibilityChanged,
            onEnd = onEnd,
        )
    } else {
        AudioActiveCallScreen(
            peerName = peerName,
            durationText = durationText,
            status = status,
            statusColor = statusColor,
            muted = muted,
            currentEndpoint = currentEndpoint,
            availableEndpoints = availableEndpoints,
            videoAllowed = videoState.allowed,
            cameraRequested = videoState.requested,
            onMute = onMute,
            onSelectEndpoint = onSelectEndpoint,
            onShowRoutePicker = { routePickerVisible = true },
            onCamera = cameraPressed,
            onEnd = onEnd,
        )
    }

    AudioRoutePicker(
        visible = routePickerVisible,
        currentEndpoint = currentEndpoint,
        availableEndpoints = availableEndpoints,
        onDismiss = { routePickerVisible = false },
        onSelectEndpoint = onSelectEndpoint,
    )
}

@Composable
private fun AudioActiveCallScreen(
    peerName: String,
    durationText: String,
    status: String,
    statusColor: Color,
    muted: Boolean,
    currentEndpoint: AudioEndpoint?,
    availableEndpoints: List<AudioEndpoint>,
    videoAllowed: Boolean,
    cameraRequested: Boolean,
    onMute: (Boolean) -> Unit,
    onSelectEndpoint: (AudioEndpoint) -> Unit,
    onShowRoutePicker: () -> Unit,
    onCamera: (Boolean) -> Unit,
    onEnd: () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val layout = callControlLayout(
            videoAllowed = videoAllowed,
            videoModeActive = false,
            widthDp = (maxWidth.value - 40f).coerceAtLeast(0f),
            heightDp = maxHeight.value,
            fontScale = LocalDensity.current.fontScale,
        )
        if (layout.scrollable) {
            ConstrainedAudioActiveCallScreen(
                peerName = peerName,
                durationText = durationText,
                status = status,
                statusColor = statusColor,
                muted = muted,
                currentEndpoint = currentEndpoint,
                availableEndpoints = availableEndpoints,
                layout = layout,
                videoAllowed = videoAllowed,
                cameraRequested = cameraRequested,
                onMute = onMute,
                onSelectEndpoint = onSelectEndpoint,
                onShowRoutePicker = onShowRoutePicker,
                onCamera = onCamera,
                onEnd = onEnd,
            )
        } else {
            RegularAudioActiveCallScreen(
                peerName = peerName,
                durationText = durationText,
                status = status,
                statusColor = statusColor,
                muted = muted,
                currentEndpoint = currentEndpoint,
                availableEndpoints = availableEndpoints,
                layout = layout,
                videoAllowed = videoAllowed,
                cameraRequested = cameraRequested,
                onMute = onMute,
                onSelectEndpoint = onSelectEndpoint,
                onShowRoutePicker = onShowRoutePicker,
                onCamera = onCamera,
                onEnd = onEnd,
            )
        }
    }
}

@Composable
private fun RegularAudioActiveCallScreen(
    peerName: String,
    durationText: String,
    status: String,
    statusColor: Color,
    muted: Boolean,
    currentEndpoint: AudioEndpoint?,
    availableEndpoints: List<AudioEndpoint>,
    layout: CallControlLayout,
    videoAllowed: Boolean,
    cameraRequested: Boolean,
    onMute: (Boolean) -> Unit,
    onSelectEndpoint: (AudioEndpoint) -> Unit,
    onShowRoutePicker: () -> Unit,
    onCamera: (Boolean) -> Unit,
    onEnd: () -> Unit,
) {
    CallScreenSurface(
        status = status,
        peerName = peerName,
        detail = durationText,
        statusColor = statusColor,
    ) {
        Text(
            text = "Звук: ${audioEndpointLabel(currentEndpoint)}",
            color = Color.White.copy(alpha = 0.68f),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(14.dp))
        AdaptiveAudioControls(
            muted = muted,
            currentEndpoint = currentEndpoint,
            availableEndpoints = availableEndpoints,
            layout = layout,
            videoAllowed = videoAllowed,
            cameraRequested = cameraRequested,
            onMute = onMute,
            onSelectEndpoint = onSelectEndpoint,
            onShowRoutePicker = onShowRoutePicker,
            onCamera = onCamera,
            onEnd = onEnd,
        )
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun ConstrainedAudioActiveCallScreen(
    peerName: String,
    durationText: String,
    status: String,
    statusColor: Color,
    muted: Boolean,
    currentEndpoint: AudioEndpoint?,
    availableEndpoints: List<AudioEndpoint>,
    layout: CallControlLayout,
    videoAllowed: Boolean,
    cameraRequested: Boolean,
    onMute: (Boolean) -> Unit,
    onSelectEndpoint: (AudioEndpoint) -> Unit,
    onShowRoutePicker: () -> Unit,
    onCamera: (Boolean) -> Unit,
    onEnd: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(CallBackgroundTop, CallBackgroundBottom))),
    ) {
        VideoFallbackContent(peerName)
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = status,
                color = statusColor,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                text = peerName,
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = durationText,
                color = Color.White.copy(alpha = 0.78f),
                style = MaterialTheme.typography.titleSmall,
            )
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.34f))
                .navigationBarsPadding()
                .heightIn(max = layout.viewportHeightDp.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Звук: ${audioEndpointLabel(currentEndpoint)}",
                color = Color.White.copy(alpha = 0.72f),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            AdaptiveAudioControls(
                muted = muted,
                currentEndpoint = currentEndpoint,
                availableEndpoints = availableEndpoints,
                layout = layout,
                videoAllowed = videoAllowed,
                cameraRequested = cameraRequested,
                onMute = onMute,
                onSelectEndpoint = onSelectEndpoint,
                onShowRoutePicker = onShowRoutePicker,
                onCamera = onCamera,
                onEnd = onEnd,
            )
        }
    }
}

@Composable
private fun VideoActiveCallScreen(
    peerName: String,
    durationText: String,
    status: String,
    statusColor: Color,
    muted: Boolean,
    currentEndpoint: AudioEndpoint?,
    availableEndpoints: List<AudioEndpoint>,
    videoState: CallVideoState<VideoRenderSource>,
    onMute: (Boolean) -> Unit,
    onSelectEndpoint: (AudioEndpoint) -> Unit,
    onShowRoutePicker: () -> Unit,
    onCamera: (Boolean) -> Unit,
    onSwitchCamera: () -> Unit,
    onVideoVisibilityChanged: (Boolean) -> Unit,
    onEnd: () -> Unit,
) {
    val localSource = videoState.localTrack
    val remoteSource = videoState.remoteTrack
    var localFrameVisible by remember(localSource) { mutableStateOf(false) }
    var remoteFrameVisible by remember(remoteSource) { mutableStateOf(false) }
    val presentation = videoCallPresentation(
        videoAllowed = videoState.allowed,
        cameraRequested = videoState.requested,
        localFrameVisible = localFrameVisible,
        remoteFrameVisible = remoteFrameVisible,
    )
    val controlsMayAutoHide = presentation.controlsMayAutoHide
    val context = LocalContext.current
    val previewPreferences = remember(context) {
        context.applicationContext.getSharedPreferences(SelfPreviewPreferencesName, Context.MODE_PRIVATE)
    }
    var controlsVisible by remember(videoState.callId) { mutableStateOf(true) }
    var previewCorner by remember(videoState.callId, previewPreferences) {
        mutableStateOf(
            storedSelfPreviewCorner(previewPreferences.getString(SelfPreviewCornerKey, null)),
        )
    }
    var draggedPreviewPosition by remember(videoState.callId) { mutableStateOf<SelfPreviewPosition?>(null) }
    var previewDragging by remember(videoState.callId) { mutableStateOf(false) }
    var topControlsHeight by remember(videoState.callId) { mutableIntStateOf(0) }
    var bottomControlsHeight by remember(videoState.callId) { mutableIntStateOf(0) }
    val toggleControls = {
        controlsVisible = nextVideoControlsVisibility(
            currentVisible = controlsVisible,
            remoteVideoVisible = controlsMayAutoHide,
            event = VideoControlsVisibilityEvent.SurfaceTapped,
        )
    }

    LaunchedEffect(videoState.callId, localSource, remoteSource, presentation.blockProximity) {
        onVideoVisibilityChanged(presentation.blockProximity)
    }
    LaunchedEffect(videoState.callId, controlsMayAutoHide, controlsVisible, previewDragging) {
        if (!controlsMayAutoHide) {
            controlsVisible = nextVideoControlsVisibility(
                currentVisible = controlsVisible,
                remoteVideoVisible = false,
                event = VideoControlsVisibilityEvent.VideoChanged,
            )
        } else if (controlsVisible && !previewDragging) {
            delay(VideoControlsAutoHideMillis)
            controlsVisible = nextVideoControlsVisibility(
                currentVisible = controlsVisible,
                remoteVideoVisible = true,
                event = VideoControlsVisibilityEvent.AutoHideElapsed,
            )
        }
    }
    DisposableEffect(videoState.callId) {
        onDispose { onVideoVisibilityChanged(false) }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(CallBackgroundTop, CallBackgroundBottom))),
    ) {
        val density = LocalDensity.current
        val layoutDirection = LocalLayoutDirection.current
        val safeDrawingInsets = WindowInsets.safeDrawing
        val controlHorizontalPadding = if (maxWidth.value < 360f) 8.dp else 12.dp
        val controlLayout = callControlLayout(
            videoAllowed = true,
            videoModeActive = true,
            widthDp = (maxWidth.value - controlHorizontalPadding.value * 2f).coerceAtLeast(0f),
            heightDp = maxHeight.value,
            fontScale = density.fontScale,
        )
        if (remoteSource != null) {
            VideoCallRenderer(
                source = remoteSource,
                mirror = false,
                localOverlay = false,
                modifier = Modifier.fillMaxSize(),
                onFrameVisibilityChanged = { remoteFrameVisible = it },
            )
        }
        if (!presentation.remoteVideoVisible) {
            VideoFallbackContent(peerName)
        }

        if (controlsMayAutoHide) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(videoState.callId, controlsMayAutoHide, controlsVisible) {
                        detectTapGestures { toggleControls() }
                    },
            )
        }

        AnimatedVisibility(
            visible = controlsVisible,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .onSizeChanged { size ->
                    if (size.height > 0) topControlsHeight = size.height
                },
            enter = fadeIn(tween(VideoControlsFadeInMillis)),
            exit = fadeOut(tween(VideoControlsFadeOutMillis)),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = status,
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color.Black.copy(alpha = 0.32f))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    color = statusColor,
                    style = MaterialTheme.typography.titleSmall,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = peerName,
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = durationText,
                    color = Color.White.copy(alpha = 0.82f),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
        }

        if (
            localSource != null &&
            videoState.requested &&
            topControlsHeight > 0 &&
            bottomControlsHeight > 0
        ) {
            val compactPreview = density.fontScale >= 1.3f
            val previewSize = selfPreviewSize(compactPreview)
            val previewWidth = with(density) { previewSize.widthDp.dp.toPx() }
            val previewHeight = with(density) { previewSize.heightDp.dp.toPx() }
            val previewBounds = selfPreviewBounds(
                containerWidth = with(density) { maxWidth.toPx() },
                containerHeight = with(density) { maxHeight.toPx() },
                previewWidth = previewWidth,
                previewHeight = previewHeight,
                safeLeft = safeDrawingInsets.getLeft(density, layoutDirection).toFloat(),
                safeTop = safeDrawingInsets.getTop(density).toFloat(),
                safeRight = safeDrawingInsets.getRight(density, layoutDirection).toFloat(),
                safeBottom = safeDrawingInsets.getBottom(density).toFloat(),
                topControlsHeight = topControlsHeight.toFloat(),
                bottomControlsHeight = bottomControlsHeight.toFloat(),
                controlsVisible = controlsVisible,
                edgeSpacing = with(density) { SelfPreviewEdgeSpacing.toPx() },
            )
            val targetPreviewPosition = draggedPreviewPosition?.let { position ->
                clampSelfPreviewPosition(position, previewBounds)
            } ?: selfPreviewPosition(previewCorner, previewBounds)
            val animatedPreviewOffset by animateIntOffsetAsState(
                targetValue = IntOffset(
                    x = targetPreviewPosition.x.roundToInt(),
                    y = targetPreviewPosition.y.roundToInt(),
                ),
                animationSpec = if (draggedPreviewPosition == null) {
                    tween(VideoPreviewSnapMillis)
                } else {
                    snap()
                },
                label = "selfPreviewOffset",
            )
            val finishPreviewDrag = {
                draggedPreviewPosition?.let { position ->
                    val corner = nearestSelfPreviewCorner(position, previewBounds)
                    previewCorner = corner
                    previewPreferences.edit().putString(SelfPreviewCornerKey, corner.name).apply()
                }
                draggedPreviewPosition = null
                previewDragging = false
            }
            Box(
                modifier = Modifier
                    .offset { animatedPreviewOffset }
                    .size(
                        width = previewSize.widthDp.dp,
                        height = previewSize.heightDp.dp,
                    )
                    .clip(RectangleShape)
                    .background(Color(0xFF172438))
                    .border(1.dp, Color.White.copy(alpha = 0.34f), RectangleShape),
            ) {
                VideoCallRenderer(
                    source = localSource,
                    mirror = videoState.facing == CameraFacing.Front,
                    localOverlay = true,
                    modifier = Modifier.fillMaxSize(),
                    onClick = toggleControls.takeIf { controlsMayAutoHide },
                    onDragStart = {
                        previewDragging = true
                        draggedPreviewPosition = clampSelfPreviewPosition(
                            SelfPreviewPosition(
                                x = animatedPreviewOffset.x.toFloat(),
                                y = animatedPreviewOffset.y.toFloat(),
                            ),
                            previewBounds,
                        )
                    },
                    onDrag = { dragX, dragY ->
                        val current = draggedPreviewPosition ?: SelfPreviewPosition(
                            x = animatedPreviewOffset.x.toFloat(),
                            y = animatedPreviewOffset.y.toFloat(),
                        )
                        draggedPreviewPosition = clampSelfPreviewPosition(
                            SelfPreviewPosition(
                                x = current.x + dragX,
                                y = current.y + dragY,
                            ),
                            previewBounds,
                        )
                    },
                    onDragEnd = finishPreviewDrag,
                    contentDescription = if (controlsMayAutoHide) {
                        if (controlsVisible) "Скрыть элементы управления" else "Показать элементы управления"
                    } else null,
                    onFrameVisibilityChanged = { localFrameVisible = it },
                )
            }
        }

        AnimatedVisibility(
            visible = controlsVisible,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .onSizeChanged { size ->
                    if (size.height > 0) bottomControlsHeight = size.height
                },
            enter = slideInVertically(
                animationSpec = tween(VideoControlsSlideMillis),
                initialOffsetY = { it },
            ) + fadeIn(tween(VideoControlsFadeInMillis)),
            exit = slideOutVertically(
                animationSpec = tween(VideoControlsSlideMillis),
                targetOffsetY = { it },
            ) + fadeOut(tween(VideoControlsFadeOutMillis)),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.34f))
                    .navigationBarsPadding()
                    .then(
                        if (controlLayout.scrollable) {
                            Modifier
                                .heightIn(max = controlLayout.viewportHeightDp.dp)
                                .verticalScroll(rememberScrollState())
                        } else {
                            Modifier
                        },
                    )
                    .padding(horizontal = controlHorizontalPadding, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                weakNetworkVideoMessage(
                    videoAllowed = videoState.allowed,
                    cameraRequested = videoState.requested,
                    networkGated = videoState.networkGated,
                )?.let { message ->
                    Text(
                        text = message,
                        color = Color(0xFFFFCA6A),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(6.dp))
                }
                if (videoState.failure != null && !videoState.sending) {
                    Text(
                        text = "Не удалось включить камеру",
                        color = Color(0xFFFFCA6A),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(6.dp))
                }
                Text(
                    text = "Звук: ${audioEndpointLabel(currentEndpoint)}",
                    color = Color.White.copy(alpha = 0.72f),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                AdaptiveVideoControls(
                    muted = muted,
                    currentEndpoint = currentEndpoint,
                    availableEndpoints = availableEndpoints,
                    layout = controlLayout,
                    cameraRequested = videoState.requested,
                    switchCameraEnabled = presentation.switchCameraEnabled,
                    onMute = onMute,
                    onSelectEndpoint = onSelectEndpoint,
                    onShowRoutePicker = onShowRoutePicker,
                    onSwitchCamera = onSwitchCamera,
                    onCamera = onCamera,
                    onEnd = onEnd,
                )
            }
        }
    }
}

private const val VideoControlsAutoHideMillis = 3_000L
private const val VideoControlsFadeInMillis = 180
private const val VideoControlsFadeOutMillis = 220
private const val VideoControlsSlideMillis = 260
private const val VideoPreviewSnapMillis = 220
private const val SelfPreviewPreferencesName = "call_ui"
private const val SelfPreviewCornerKey = "self_preview_corner"
private val SelfPreviewEdgeSpacing = 12.dp

@Composable
private fun VideoFallbackContent(peerName: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.13f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = contactInitial(peerName, peerName),
                color = Color.White,
                fontSize = 44.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun AdaptiveAudioControls(
    muted: Boolean,
    currentEndpoint: AudioEndpoint?,
    availableEndpoints: List<AudioEndpoint>,
    layout: CallControlLayout,
    videoAllowed: Boolean,
    cameraRequested: Boolean,
    onMute: (Boolean) -> Unit,
    onSelectEndpoint: (AudioEndpoint) -> Unit,
    onShowRoutePicker: () -> Unit,
    onCamera: (Boolean) -> Unit,
    onEnd: () -> Unit,
) {
    val compact = layout.columns == 2
    if (videoAllowed && !compact) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            CameraCallAction(cameraRequested, Modifier.weight(1f), onCamera)
            AudioRouteAction(
                currentEndpoint,
                availableEndpoints,
                Modifier.weight(1f),
                onSelectEndpoint,
                onShowRoutePicker,
                compact = true,
            )
            MuteCallAction(muted, Modifier.weight(1f), onMute, compact = true)
            EndCallAction(Modifier.weight(1f), onEnd, compact = true)
        }
    } else if (videoAllowed) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                CameraCallAction(cameraRequested, Modifier.weight(1f), onCamera)
                AudioRouteAction(
                    currentEndpoint,
                    availableEndpoints,
                    Modifier.weight(1f),
                    onSelectEndpoint,
                    onShowRoutePicker,
                    compact = true,
                )
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                MuteCallAction(muted, Modifier.weight(1f), onMute, compact = true)
                EndCallAction(Modifier.weight(1f), onEnd, compact = true)
            }
        }
    } else if (!compact) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            AudioRouteAction(
                currentEndpoint,
                availableEndpoints,
                Modifier.weight(1f),
                onSelectEndpoint,
                onShowRoutePicker,
            )
            MuteCallAction(muted, Modifier.weight(1f), onMute)
            EndCallAction(Modifier.weight(1f), onEnd)
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                AudioRouteAction(
                    currentEndpoint,
                    availableEndpoints,
                    Modifier.weight(1f),
                    onSelectEndpoint,
                    onShowRoutePicker,
                    compact = true,
                )
                MuteCallAction(muted, Modifier.weight(1f), onMute, compact = true)
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                Spacer(Modifier.weight(1f))
                EndCallAction(Modifier.weight(1f), onEnd, compact = true)
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun AdaptiveVideoControls(
    muted: Boolean,
    currentEndpoint: AudioEndpoint?,
    availableEndpoints: List<AudioEndpoint>,
    layout: CallControlLayout,
    cameraRequested: Boolean,
    switchCameraEnabled: Boolean,
    onMute: (Boolean) -> Unit,
    onSelectEndpoint: (AudioEndpoint) -> Unit,
    onShowRoutePicker: () -> Unit,
    onSwitchCamera: () -> Unit,
    onCamera: (Boolean) -> Unit,
    onEnd: () -> Unit,
) {
    val buttonSize = layout.buttonSizeDp.dp
    Row(modifier = Modifier.fillMaxWidth()) {
        SwitchCameraCallAction(switchCameraEnabled, Modifier.weight(1f), onSwitchCamera, buttonSize)
        CameraCallAction(cameraRequested, Modifier.weight(1f), onCamera, buttonSize)
        AudioRouteAction(
            currentEndpoint = currentEndpoint,
            availableEndpoints = availableEndpoints,
            modifier = Modifier.weight(1f),
            onSelectEndpoint = onSelectEndpoint,
            onShowPicker = onShowRoutePicker,
            compact = true,
            buttonSize = buttonSize,
        )
        MuteCallAction(muted, Modifier.weight(1f), onMute, compact = true, buttonSize = buttonSize)
        EndCallAction(Modifier.weight(1f), onEnd, compact = true, buttonSize = buttonSize)
    }
}

@Composable
private fun SwitchCameraCallAction(
    enabled: Boolean,
    modifier: Modifier,
    onSwitchCamera: () -> Unit,
    buttonSize: Dp = CompactCallActionSizeDp.dp,
) {
    RoundCallAction(
        label = "Повернуть",
        modifier = modifier,
        contentDescription = "Повернуть камеру",
        color = Color(0xFF33465F),
        enabled = enabled,
        onClick = onSwitchCamera,
        iconResource = R.drawable.ic_camera_switch,
        buttonSize = buttonSize,
        labelMaxLines = 1,
    )
}

@Composable
private fun CameraCallAction(
    requested: Boolean,
    modifier: Modifier,
    onCamera: (Boolean) -> Unit,
    buttonSize: Dp = CompactCallActionSizeDp.dp,
) {
    RoundCallAction(
        label = "Камера",
        modifier = modifier,
        contentDescription = if (requested) "Выключить камеру" else "Включить камеру",
        color = if (requested) Color(0xFF2A8C76) else Color(0xFF33465F),
        onClick = { onCamera(!requested) },
        iconResource = R.drawable.ic_videocam,
        buttonSize = buttonSize,
        labelMaxLines = 1,
    )
}

@Composable
private fun EndCallAction(
    modifier: Modifier,
    onEnd: () -> Unit,
    compact: Boolean = false,
    buttonSize: Dp = if (compact) CompactCallActionSizeDp.dp else 72.dp,
) {
    RoundCallAction(
        label = "Завершить",
        modifier = modifier,
        color = CallRejectRed,
        onClick = onEnd,
        iconRotation = 135f,
        buttonSize = buttonSize,
        labelMaxLines = 1,
    )
}

@Composable
internal fun MuteCallAction(
    muted: Boolean,
    modifier: Modifier = Modifier,
    onMute: (Boolean) -> Unit,
    compact: Boolean = false,
    buttonSize: Dp = if (compact) CompactCallActionSizeDp.dp else 72.dp,
) {
    RoundCallAction(
        label = "Микрофон",
        modifier = modifier,
        contentDescription = if (muted) "Включить микрофон" else "Выключить микрофон",
        color = if (muted) Color(0xFF55708F) else Color(0xFF33465F),
        onClick = { onMute(!muted) },
        iconResource = if (muted) R.drawable.ic_mic_off else R.drawable.ic_mic,
        buttonSize = buttonSize,
        labelMaxLines = 1,
    )
}

@Composable
internal fun AudioRouteAction(
    currentEndpoint: AudioEndpoint?,
    availableEndpoints: List<AudioEndpoint>,
    modifier: Modifier = Modifier,
    onSelectEndpoint: (AudioEndpoint) -> Unit,
    onShowPicker: () -> Unit,
    compact: Boolean = false,
    buttonSize: Dp = if (compact) CompactCallActionSizeDp.dp else 72.dp,
) {
    val directRoute = directAudioRoute(currentEndpoint, availableEndpoints)
    RoundCallAction(
        label = "Звук",
        modifier = modifier,
        contentDescription = when (directRoute?.type) {
            CallEndpointCompat.TYPE_SPEAKER -> "Включить громкую связь"
            CallEndpointCompat.TYPE_EARPIECE -> "Выключить громкую связь"
            else -> "Выбрать устройство звука. Сейчас: ${audioEndpointLabel(currentEndpoint)}"
        },
        color = Color(0xFF33465F),
        enabled = availableEndpoints.isNotEmpty(),
        onClick = {
            if (directRoute != null) {
                onSelectEndpoint(directRoute)
            } else {
                onShowPicker()
            }
        },
        iconResource = audioEndpointIcon(currentEndpoint),
        buttonSize = buttonSize,
        labelMaxLines = 1,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AudioRoutePicker(
    visible: Boolean,
    currentEndpoint: AudioEndpoint?,
    availableEndpoints: List<AudioEndpoint>,
    onDismiss: () -> Unit,
    onSelectEndpoint: (AudioEndpoint) -> Unit,
) {
    if (!visible) return
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            text = "Куда выводить звук",
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        availableEndpoints.forEach { endpoint ->
            val selected = endpoint.id == currentEndpoint?.id
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        onDismiss()
                        onSelectEndpoint(endpoint)
                    }
                    .padding(PaddingValues(horizontal = 24.dp, vertical = 16.dp)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(audioEndpointIcon(endpoint)),
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                )
                Column(modifier = Modifier.weight(1f).padding(start = 18.dp)) {
                    Text(audioEndpointLabel(endpoint), style = MaterialTheme.typography.titleMedium)
                    if (selected) {
                        Text(
                            text = "Используется сейчас",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                if (selected) {
                    Text("✓", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.navigationBarsPadding().height(12.dp))
    }
}

internal fun directAudioRoute(current: AudioEndpoint?, available: List<AudioEndpoint>): AudioEndpoint? {
    val phoneRouteTypes = setOf(CallEndpointCompat.TYPE_EARPIECE, CallEndpointCompat.TYPE_SPEAKER)
    if (available.size != 2 || available.map { it.type }.toSet() != phoneRouteTypes) return null
    val nextType = if (current?.type == CallEndpointCompat.TYPE_SPEAKER) {
        CallEndpointCompat.TYPE_EARPIECE
    } else {
        CallEndpointCompat.TYPE_SPEAKER
    }
    return available.firstOrNull { it.type == nextType }
}

internal fun speakerRouteOnCameraPress(
    cameraRequested: Boolean,
    current: AudioEndpoint?,
    available: List<AudioEndpoint>,
): AudioEndpoint? = if (cameraRequested && current?.type == CallEndpointCompat.TYPE_EARPIECE) {
    available.firstOrNull { it.type == CallEndpointCompat.TYPE_SPEAKER }
} else {
    null
}

private fun audioEndpointLabel(endpoint: AudioEndpoint?): String = when (endpoint?.type) {
    CallEndpointCompat.TYPE_EARPIECE -> "Телефон"
    CallEndpointCompat.TYPE_SPEAKER -> "Динамик"
    CallEndpointCompat.TYPE_BLUETOOTH -> "Bluetooth"
    CallEndpointCompat.TYPE_WIRED_HEADSET -> "Наушники"
    CallEndpointCompat.TYPE_STREAMING -> "Другое устройство"
    else -> "Устройство"
}

private fun audioEndpointIcon(endpoint: AudioEndpoint?): Int = when (endpoint?.type) {
    CallEndpointCompat.TYPE_BLUETOOTH -> R.drawable.ic_bluetooth
    CallEndpointCompat.TYPE_WIRED_HEADSET -> R.drawable.ic_headset
    CallEndpointCompat.TYPE_SPEAKER -> R.drawable.ic_volume_up
    CallEndpointCompat.TYPE_EARPIECE -> R.drawable.ic_phone_in_talk
    else -> R.drawable.ic_call
}

@Composable
fun EndedCallScreen(peerName: String, reason: CallEndReason?) {
    CallScreenSurface(status = if (reason == CallEndReason.Busy) "Занято" else "Звонок завершён", peerName = peerName) {
        Spacer(Modifier.height(18.dp))
    }
}
