package org.tinitalk.ui.call

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

    LaunchedEffect(videoState.callId, videoMode, currentEndpoint, availableEndpoints) {
        if (videoMode) {
            routePickerVisible = false
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
            videoState = videoState,
            onMute = onMute,
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
    videoState: CallVideoState<VideoRenderSource>,
    onMute: (Boolean) -> Unit,
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
    var controlsVisible by remember(videoState.callId) { mutableStateOf(true) }
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
    LaunchedEffect(videoState.callId, controlsMayAutoHide, controlsVisible) {
        if (!controlsMayAutoHide) {
            controlsVisible = nextVideoControlsVisibility(
                currentVisible = controlsVisible,
                remoteVideoVisible = false,
                event = VideoControlsVisibilityEvent.VideoChanged,
            )
        } else if (controlsVisible) {
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
        val controlLayout = callControlLayout(
            videoAllowed = true,
            videoModeActive = true,
            widthDp = (maxWidth.value - 24f).coerceAtLeast(0f),
            heightDp = maxHeight.value,
            fontScale = LocalDensity.current.fontScale,
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
                .statusBarsPadding(),
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

        if (localSource != null && videoState.requested) {
            val compactPreview = LocalDensity.current.fontScale >= 1.3f
            val previewSize = selfPreviewSize(compactPreview)
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = if (compactPreview) 96.dp else 112.dp, end = 14.dp)
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
                .fillMaxWidth(),
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
                    .padding(horizontal = 12.dp, vertical = 12.dp),
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
                    layout = controlLayout,
                    cameraRequested = videoState.requested,
                    switchCameraEnabled = presentation.switchCameraEnabled,
                    onMute = onMute,
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
            AudioRouteAction(
                currentEndpoint,
                availableEndpoints,
                Modifier.weight(1f),
                onSelectEndpoint,
                onShowRoutePicker,
                compact = true,
            )
            CameraCallAction(cameraRequested, Modifier.weight(1f), onCamera)
            MuteCallAction(muted, Modifier.weight(1f), onMute, compact = true)
            EndCallAction(Modifier.weight(1f), onEnd, compact = true)
        }
    } else if (videoAllowed) {
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
                CameraCallAction(cameraRequested, Modifier.weight(1f), onCamera)
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                MuteCallAction(muted, Modifier.weight(1f), onMute, compact = true)
                EndCallAction(Modifier.weight(1f), onEnd, compact = true)
            }
        }
    } else if (!compact) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            MuteCallAction(muted, Modifier.weight(1f), onMute)
            AudioRouteAction(
                currentEndpoint,
                availableEndpoints,
                Modifier.weight(1f),
                onSelectEndpoint,
                onShowRoutePicker,
            )
            EndCallAction(Modifier.weight(1f), onEnd)
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                MuteCallAction(muted, Modifier.weight(1f), onMute, compact = true)
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
    layout: CallControlLayout,
    cameraRequested: Boolean,
    switchCameraEnabled: Boolean,
    onMute: (Boolean) -> Unit,
    onSwitchCamera: () -> Unit,
    onCamera: (Boolean) -> Unit,
    onEnd: () -> Unit,
) {
    if (layout.columns == 4) {
        Row(modifier = Modifier.fillMaxWidth()) {
            SwitchCameraCallAction(switchCameraEnabled, Modifier.weight(1f), onSwitchCamera)
            CameraCallAction(cameraRequested, Modifier.weight(1f), onCamera)
            MuteCallAction(muted, Modifier.weight(1f), onMute, compact = true)
            EndCallAction(Modifier.weight(1f), onEnd, compact = true)
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                SwitchCameraCallAction(switchCameraEnabled, Modifier.weight(1f), onSwitchCamera)
                CameraCallAction(cameraRequested, Modifier.weight(1f), onCamera)
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                MuteCallAction(muted, Modifier.weight(1f), onMute, compact = true)
                EndCallAction(Modifier.weight(1f), onEnd, compact = true)
            }
        }
    }
}

@Composable
private fun SwitchCameraCallAction(
    enabled: Boolean,
    modifier: Modifier,
    onSwitchCamera: () -> Unit,
) {
    RoundCallAction(
        label = "Повернуть",
        modifier = modifier,
        contentDescription = "Повернуть камеру",
        color = Color(0xFF33465F),
        enabled = enabled,
        onClick = onSwitchCamera,
        iconResource = R.drawable.ic_camera_switch,
        buttonSize = CompactCallActionSizeDp.dp,
        labelMaxLines = 2,
        showLabel = false,
    )
}

@Composable
private fun CameraCallAction(
    requested: Boolean,
    modifier: Modifier,
    onCamera: (Boolean) -> Unit,
) {
    RoundCallAction(
        label = "Камера",
        modifier = modifier,
        contentDescription = if (requested) "Выключить камеру" else "Включить камеру",
        color = if (requested) Color(0xFF2A8C76) else Color(0xFF33465F),
        onClick = { onCamera(!requested) },
        iconResource = R.drawable.ic_videocam,
        buttonSize = CompactCallActionSizeDp.dp,
        labelMaxLines = 2,
        showLabel = false,
    )
}

@Composable
private fun EndCallAction(
    modifier: Modifier,
    onEnd: () -> Unit,
    compact: Boolean = false,
) {
    RoundCallAction(
        label = "Завершить",
        modifier = modifier,
        color = CallRejectRed,
        onClick = onEnd,
        iconRotation = 135f,
        buttonSize = if (compact) CompactCallActionSizeDp.dp else 72.dp,
        labelMaxLines = if (compact) 2 else 1,
        showLabel = false,
    )
}

@Composable
internal fun MuteCallAction(
    muted: Boolean,
    modifier: Modifier = Modifier,
    onMute: (Boolean) -> Unit,
    compact: Boolean = false,
) {
    RoundCallAction(
        label = "Микрофон",
        modifier = modifier,
        contentDescription = if (muted) "Включить микрофон" else "Выключить микрофон",
        color = if (muted) Color(0xFF55708F) else Color(0xFF33465F),
        onClick = { onMute(!muted) },
        iconResource = if (muted) R.drawable.ic_mic_off else R.drawable.ic_mic,
        buttonSize = if (compact) CompactCallActionSizeDp.dp else 72.dp,
        labelMaxLines = if (compact) 2 else 1,
        showLabel = false,
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
        buttonSize = if (compact) CompactCallActionSizeDp.dp else 72.dp,
        labelMaxLines = if (compact) 2 else 1,
        showLabel = false,
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
    else -> R.drawable.ic_call
}

@Composable
fun EndedCallScreen(peerName: String, reason: CallEndReason?) {
    CallScreenSurface(status = if (reason == CallEndReason.Busy) "Занято" else "Звонок завершён", peerName = peerName) {
        Spacer(Modifier.height(18.dp))
    }
}
