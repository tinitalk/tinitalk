package org.tinitalk.ui.call

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.tinitalk.call.CallVideoState
import org.tinitalk.media.VideoRenderSource
import org.tinitalk.telecom.AudioEndpoint

@Composable
internal fun ScreenSharingViewer(
    peerName: String,
    durationText: String,
    status: String,
    videoState: CallVideoState<VideoRenderSource>,
    muted: Boolean,
    currentEndpoint: AudioEndpoint?,
    availableEndpoints: List<AudioEndpoint>,
    onMute: (Boolean) -> Unit,
    onSelectEndpoint: (AudioEndpoint) -> Unit,
    onShowRoutePicker: () -> Unit,
    onCamera: (Boolean) -> Unit,
    onVideoVisibilityChanged: (Boolean) -> Unit,
    onEnd: () -> Unit,
) {
    DisposableEffect(videoState.callKey) {
        onVideoVisibilityChanged(true)
        onDispose { onVideoVisibilityChanged(false) }
    }
    Column(Modifier.fillMaxSize().background(Color(0xFF101722)).navigationBarsPadding()) {
        Text(peerName, Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            color = Color.White, style = MaterialTheme.typography.titleMedium,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        Text("$durationText · $status", Modifier.fillMaxWidth().padding(8.dp),
            color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        ScreenImage(videoState.remoteTrack, videoState.screen.remoteId, Modifier.weight(1f).fillMaxWidth())
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly) {
            MuteCallAction(muted, Modifier.weight(1f), onMute, compact = true)
            AudioRouteAction(currentEndpoint, availableEndpoints, Modifier.weight(1f), onSelectEndpoint, onShowRoutePicker, compact = true)
            CameraCallAction(videoState.requested, Modifier.weight(1f), onCamera)
            EndCallAction(Modifier.weight(1f), onEnd, compact = true)
        }
    }
}

@Composable
private fun ScreenImage(source: VideoRenderSource?, shareId: String?, modifier: Modifier) {
    var aspect by remember(shareId, source) { mutableFloatStateOf(9f / 16f) }
    var frameVisible by remember(shareId, source) { mutableStateOf(false) }
    var zoom by remember(shareId, aspect) { mutableFloatStateOf(1f) }
    var pan by remember(shareId, aspect) { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    BoxWithConstraints(modifier.background(Color.Black).clipToBounds().onSizeChanged {
        if (viewport != it) { viewport = it; zoom = 1f; pan = Offset.Zero }
    }, contentAlignment = Alignment.Center) {
        val imageWidth = minOf(maxWidth, maxHeight * aspect)
        val imageHeight = imageWidth / aspect
        val imagePixels = with(LocalDensity.current) { Offset(imageWidth.toPx(), imageHeight.toPx()) }
        if (source != null) {
            VideoCallRenderer(
                source, mirror = false, localOverlay = false,
                modifier = Modifier.size(imageWidth, imageHeight).graphicsLayer {
                    scaleX = zoom; scaleY = zoom
                    translationX = pan.x; translationY = pan.y
                },
                contentDescription = "Экран собеседника",
                keepLastFrame = true,
                onFrameSizeChanged = { w, h -> if (w > 0 && h > 0) aspect = w.toFloat() / h },
                onFrameVisibilityChanged = { frameVisible = it },
            )
        }
        // Gestures belong to the viewport, so the surface cannot swallow them.
        Box(Modifier.fillMaxSize()
            .pointerInput(shareId, imagePixels) {
                detectTransformGestures { centroid, movement, scale, _ ->
                    val next = (zoom * scale).coerceIn(1f, 4f)
                    val relative = centroid - Offset(size.width / 2f, size.height / 2f)
                    val shifted = (pan - relative) * (next / zoom) + relative + movement
                    val limitX = ((imagePixels.x * next - size.width) / 2f).coerceAtLeast(0f)
                    val limitY = ((imagePixels.y * next - size.height) / 2f).coerceAtLeast(0f)
                    pan = Offset(shifted.x.coerceIn(-limitX, limitX), shifted.y.coerceIn(-limitY, limitY))
                    zoom = next
                }
            }
            .pointerInput(shareId) { detectTapGestures(onDoubleTap = { zoom = 1f; pan = Offset.Zero }) })
        if (!frameVisible) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(Modifier.size(28.dp), color = Color.White, strokeWidth = 2.dp)
                Text("Ожидаем изображение…", Modifier.padding(12.dp), color = Color.White)
            }
        }
        if (zoom > 1f) TextButton(
            onClick = { zoom = 1f; pan = Offset.Zero },
            modifier = Modifier.align(Alignment.TopEnd).background(Color.Black.copy(alpha = 0.65f)),
        ) { Text("Целиком", color = Color.White) }
    }
}
