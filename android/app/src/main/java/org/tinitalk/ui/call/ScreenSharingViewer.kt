package org.tinitalk.ui.call

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import org.tinitalk.call.CallVideoState
import org.tinitalk.call.ConnectionHealth
import org.tinitalk.media.VideoRenderSource
import org.tinitalk.telecom.AudioEndpoint
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val ScreenPanelBackground = Color(0xFF263447).copy(alpha = 0.9f)

@Composable
internal fun ScreenSharingViewer(
    peerName: String,
    durationText: String,
    connectionHealth: ConnectionHealth,
    videoState: CallVideoState<VideoRenderSource>,
    muted: Boolean,
    currentEndpoint: AudioEndpoint?,
    availableEndpoints: List<AudioEndpoint>,
    onMute: (Boolean) -> Unit,
    onSelectEndpoint: (AudioEndpoint) -> Unit,
    onShowRoutePicker: () -> Unit,
    routePickerVisible: Boolean,
    onVideoVisibilityChanged: (Boolean) -> Unit,
    onEnd: () -> Unit,
) {
    val shareId = videoState.screen.remoteId
    val source = videoState.remoteTrack.takeIf { videoState.screen.ready && videoState.remoteSending }
    var frameVisible by remember(shareId, source) { mutableStateOf(false) }
    var controlsVisible by remember(shareId) { mutableStateOf(true) }
    var interaction by remember(shareId) { mutableIntStateOf(0) }
    val panelProgress = animateFloatAsState(
        targetValue = if (controlsVisible) 1f else 0f,
        animationSpec = tween(260), label = "screenPanels",
    )
    val panelsHidden by remember { derivedStateOf { panelProgress.value == 0f } }
    val touch: () -> Unit = { interaction++ }
    LaunchedEffect(shareId, frameVisible, controlsVisible, interaction, routePickerVisible) {
        if (!frameVisible || routePickerVisible) {
            controlsVisible = true
        } else if (controlsVisible) {
            delay(3_000)
            controlsVisible = false
        }
    }
    DisposableEffect(videoState.callKey) {
        onVideoVisibilityChanged(true)
        onDispose { onVideoVisibilityChanged(false) }
    }
    // Keep both the image and our panels outside the phone's system bars and cutouts.
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding().clipToBounds()) {
        val status = when {
            connectionHealth == ConnectionHealth.Reconnecting || connectionHealth == ConnectionHealth.Connecting -> "Восстанавливаем связь…"
            !frameVisible -> "Подключаем показ…"
            else -> "Идёт показ экрана"
        }
        val layout = callControlLayout(
            videoAllowed = false, videoModeActive = false,
            widthDp = (maxWidth.value - 24f).coerceAtLeast(0f), heightDp = maxHeight.value,
            fontScale = LocalDensity.current.fontScale,
        )
        val panelSemantics = if (controlsVisible) Modifier else Modifier.clearAndSetSemantics {}
        ScreenSharingPanels(
            progress = panelProgress,
            header = {
                Column(Modifier.fillMaxWidth().then(panelSemantics).background(ScreenPanelBackground)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(status, color = Color.White.copy(alpha = 0.75f),
                        style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                    Text(peerName, Modifier.padding(top = 6.dp), color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    Text(durationText, Modifier.padding(top = 4.dp), color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodySmall)
                }
            },
            controls = {
                Box(Modifier.fillMaxWidth().then(panelSemantics).background(ScreenPanelBackground)
                    .padding(horizontal = 12.dp, vertical = 16.dp)) {
                    AdaptiveAudioControls(
                        muted, currentEndpoint, availableEndpoints, layout,
                        videoAllowed = false, cameraRequested = false,
                        onMute = { touch(); onMute(it) },
                        onSelectEndpoint = { touch(); onSelectEndpoint(it) },
                        onShowRoutePicker = { touch(); onShowRoutePicker() },
                        onCamera = {}, onEnd = onEnd,
                    )
                }
            },
        ) {
            ScreenImage(source, shareId, controlsVisible, Modifier.fillMaxSize(),
                onFrameVisibilityChanged = { frameVisible = it },
                onTap = { touch(); if (frameVisible) controlsVisible = !controlsVisible },
                onInteraction = touch,
            )
        }
        if (panelsHidden) {
            Text(status, Modifier.align(Alignment.TopCenter)
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .background(ScreenPanelBackground, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
                color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** A single slide progress reserves exactly the space exposed by the two panels. */
@Composable
internal fun ScreenSharingPanels(
    progress: State<Float>,
    header: @Composable () -> Unit,
    controls: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    Layout(
        modifier = Modifier.fillMaxSize().clipToBounds(),
        content = {
            Box(Modifier.clipToBounds()) { content() }
            Box { header() }
            Box { controls() }
        },
    ) { children, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val panelConstraints = constraints.copy(minHeight = 0)
        val top = children[1].measure(panelConstraints)
        val bottom = children[2].measure(panelConstraints)
        val shown = progress.value.coerceIn(0f, 1f)
        val topSpace = (top.height * shown).roundToInt()
        val bottomSpace = (bottom.height * shown).roundToInt()
        val image = children[0].measure(Constraints.fixed(width, (height - topSpace - bottomSpace).coerceAtLeast(0)))
        layout(width, height) {
            image.placeRelative(0, topSpace)
            top.placeRelative(0, topSpace - top.height)
            bottom.placeRelative(0, height - bottomSpace)
        }
    }
}

@Composable
private fun ScreenImage(
    source: VideoRenderSource?,
    shareId: String?,
    controlsVisible: Boolean,
    modifier: Modifier,
    onFrameVisibilityChanged: (Boolean) -> Unit,
    onTap: () -> Unit,
    onInteraction: () -> Unit,
) {
    val currentTap by rememberUpdatedState(onTap)
    val currentInteraction by rememberUpdatedState(onInteraction)
    var aspect by remember(shareId, source) { mutableFloatStateOf(9f / 16f) }
    var frameVisible by remember(shareId, source) { mutableStateOf(false) }
    var zoom by remember(shareId, aspect) { mutableFloatStateOf(1f) }
    var pan by remember(shareId, aspect) { mutableStateOf(Offset.Zero) }
    // Store pan relative to the fitted image so sliding panels do not reset the user's zoom.
    BoxWithConstraints(modifier.background(Color.Black).clipToBounds(), contentAlignment = Alignment.Center) {
        val imageWidth = minOf(maxWidth, maxHeight * aspect)
        val imageHeight = imageWidth / aspect
        val imagePixels = with(LocalDensity.current) { Offset(imageWidth.toPx(), imageHeight.toPx()) }
        val viewportPixels = with(LocalDensity.current) { Offset(maxWidth.toPx(), maxHeight.toPx()) }
        fun limitedPan(scale: Float): Offset {
            val limitX = ((imagePixels.x * scale - viewportPixels.x) / 2f).coerceAtLeast(0f)
            val limitY = ((imagePixels.y * scale - viewportPixels.y) / 2f).coerceAtLeast(0f)
            return Offset((pan.x * imagePixels.x).coerceIn(-limitX, limitX),
                (pan.y * imagePixels.y).coerceIn(-limitY, limitY))
        }
        if (source != null) {
            VideoCallRenderer(
                source, mirror = false, localOverlay = false,
                modifier = Modifier.size(imageWidth, imageHeight).graphicsLayer {
                    scaleX = zoom; scaleY = zoom
                    val translation = limitedPan(zoom)
                    translationX = translation.x; translationY = translation.y
                },
                contentDescription = "Экран собеседника",
                keepLastFrame = true,
                onFrameSizeChanged = { w, h -> if (w > 0 && h > 0) aspect = w.toFloat() / h },
                onFrameVisibilityChanged = { frameVisible = it },
            )
        }
        // Gestures belong to the viewport, so the surface cannot swallow them.
        Box(Modifier.fillMaxSize()
            .pointerInput(shareId, imagePixels, viewportPixels) {
                detectTransformGestures { centroid, movement, scale, _ ->
                    currentInteraction()
                    val next = (zoom * scale).coerceIn(1f, 4f)
                    val relative = centroid - Offset(size.width / 2f, size.height / 2f)
                    val shifted = (limitedPan(zoom) - relative) * (next / zoom) + relative + movement
                    val limitX = ((imagePixels.x * next - size.width) / 2f).coerceAtLeast(0f)
                    val limitY = ((imagePixels.y * next - size.height) / 2f).coerceAtLeast(0f)
                    pan = Offset(shifted.x.coerceIn(-limitX, limitX) / imagePixels.x.coerceAtLeast(1f),
                        shifted.y.coerceIn(-limitY, limitY) / imagePixels.y.coerceAtLeast(1f))
                    zoom = next
                }
            }
            .pointerInput(shareId) {
                detectTapGestures(
                    onTap = { currentTap() },
                    onDoubleTap = { currentInteraction(); zoom = 1f; pan = Offset.Zero },
                )
            })
        if (!frameVisible) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(Modifier.size(28.dp), color = Color.White, strokeWidth = 2.dp)
                Text("Ожидаем изображение…", Modifier.padding(12.dp), color = Color.White)
            }
        }
        if (controlsVisible && zoom > 1f) TextButton(
            onClick = { currentInteraction(); zoom = 1f; pan = Offset.Zero },
            modifier = Modifier.align(Alignment.CenterEnd).background(Color.Black.copy(alpha = 0.65f)),
        ) { Text("Целиком", color = Color.White) }
    }
    LaunchedEffect(frameVisible) { onFrameVisibilityChanged(frameVisible) }
}
