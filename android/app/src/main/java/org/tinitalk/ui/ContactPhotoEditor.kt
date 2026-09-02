package org.tinitalk.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.tinitalk.data.NormalizedCropSquare
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactPhotoActionSheet(
    hasPhoto: Boolean,
    busy: Boolean,
    onGallery: () -> Unit,
    onFiles: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Фото контакта", style = MaterialTheme.typography.titleLarge)
            Button(
                onClick = onGallery,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(18.dp),
            ) {
                Text("Выбрать из галереи")
            }
            Button(
                onClick = onFiles,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(18.dp),
            ) {
                Text("Выбрать из файлов")
            }
            if (hasPhoto) {
                TextButton(
                    onClick = onRemove,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                ) {
                    Text("Удалить фото")
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
fun ContactPhotoCropOverlay(
    state: ContactPhotoEditorState,
    onCancel: () -> Unit,
    onDone: (NormalizedCropSquare) -> Unit,
) {
    val draft = state.draft ?: return
    var cropViewport by remember(draft.id) { mutableStateOf(DefaultCropViewport) }
    var transform by remember(draft.id) { mutableStateOf(defaultCropTransform(draft.preview, cropViewport)) }
    val visibleTransform = clampCropTransform(draft.preview.width, draft.preview.height, transform, cropViewport)
    val crop = normalizedCropForViewport(draft.preview.width, draft.preview.height, visibleTransform, cropViewport)
    val previewBitmap = remember(draft.id) { draft.preview.asImageBitmap() }

    LaunchedEffect(draft.id, cropViewport) {
        transform = clampCropTransform(draft.preview.width, draft.preview.height, transform, cropViewport)
    }

    BackHandler(enabled = true, onBack = onCancel)
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background.copy(alpha = 0.98f),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Настройте фото", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(18.dp))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("contact-photo-crop-area"),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(280.dp)
                        .onSizeChanged { size ->
                            if (size.width > 0 && size.height > 0) cropViewport = size
                        }
                        .clip(CircleShape)
                        .background(Color.Black)
                        .pointerInput(draft.id, cropViewport) {
                            detectTransformGestures { centroid, pan, zoom, _ ->
                                transform = applyCropGesture(
                                    draft.preview.width,
                                    draft.preview.height,
                                    transform,
                                    centroid,
                                    pan,
                                    zoom,
                                    cropViewport,
                                )
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize(),
                    ) {
                        val source = cropRectForViewport(
                            imageWidth = draft.preview.width,
                            imageHeight = draft.preview.height,
                            transform = visibleTransform,
                            viewport = cropViewport,
                        )
                        val srcLeft = source.left.roundToInt().coerceIn(0, draft.preview.width - 1)
                        val srcTop = source.top.roundToInt().coerceIn(0, draft.preview.height - 1)
                        val srcSize = source.size.roundToInt()
                            .coerceIn(1, min(draft.preview.width - srcLeft, draft.preview.height - srcTop))
                        drawImage(
                            image = previewBitmap,
                            srcOffset = IntOffset(srcLeft, srcTop),
                            srcSize = IntSize(srcSize, srcSize),
                            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                            filterQuality = FilterQuality.High,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape),
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(
                    onClick = onCancel,
                    enabled = state.phase != ContactPhotoEditorPhase.Saving,
                    modifier = Modifier.weight(1f).height(54.dp),
                ) {
                    Text("Отмена")
                }
                Button(
                    onClick = { onDone(crop) },
                    enabled = state.phase != ContactPhotoEditorPhase.Saving,
                    modifier = Modifier.weight(1f).height(54.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(contentColor = Color.White),
                ) {
                    if (state.phase == ContactPhotoEditorPhase.Saving) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Готово")
                    }
                }
            }
        }
    }
}

data class CropTransform(
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float,
)

data class CropSourceRect(
    val left: Float,
    val top: Float,
    val size: Float,
)

private val DefaultCropViewport = IntSize(280, 280)
private const val MaxCropScale = 12f

fun defaultCropTransform(bitmap: Bitmap, viewport: IntSize = DefaultCropViewport): CropTransform =
    CropTransform(scale = minCropScale(bitmap.width, bitmap.height, viewport), offsetX = 0f, offsetY = 0f)

fun clampCropTransform(
    imageWidth: Int,
    imageHeight: Int,
    transform: CropTransform,
    viewport: IntSize = DefaultCropViewport,
): CropTransform {
    val minScale = minCropScale(imageWidth, imageHeight, viewport)
    val scale = transform.scale.coerceIn(minScale, max(MaxCropScale, minScale))
    val baseScale = fitScale(imageWidth, imageHeight, viewport)
    val displayedWidth = imageWidth * baseScale * scale
    val displayedHeight = imageHeight * baseScale * scale
    val maxOffsetX = ((displayedWidth - viewport.width) / 2f).coerceAtLeast(0f)
    val maxOffsetY = ((displayedHeight - viewport.height) / 2f).coerceAtLeast(0f)
    return CropTransform(
        scale = scale,
        offsetX = transform.offsetX.coerceIn(-maxOffsetX, maxOffsetX),
        offsetY = transform.offsetY.coerceIn(-maxOffsetY, maxOffsetY),
    )
}

fun applyCropGesture(
    imageWidth: Int,
    imageHeight: Int,
    transform: CropTransform,
    centroid: Offset,
    pan: Offset,
    zoom: Float,
    viewport: IntSize = DefaultCropViewport,
): CropTransform {
    val current = clampCropTransform(imageWidth, imageHeight, transform, viewport)
    val nextScale = current.scale * zoom
    val scaleChange = nextScale / current.scale
    val centroidFromCenter = Offset(
        centroid.x - viewport.width / 2f,
        centroid.y - viewport.height / 2f,
    )
    val zoomedOffsetX = centroidFromCenter.x - (centroidFromCenter.x - current.offsetX) * scaleChange
    val zoomedOffsetY = centroidFromCenter.y - (centroidFromCenter.y - current.offsetY) * scaleChange
    return clampCropTransform(
        imageWidth,
        imageHeight,
        CropTransform(
            scale = nextScale,
            offsetX = zoomedOffsetX + pan.x,
            offsetY = zoomedOffsetY + pan.y,
        ),
        viewport,
    )
}

fun normalizedCropForViewport(
    imageWidth: Int,
    imageHeight: Int,
    transform: CropTransform,
    viewport: IntSize = DefaultCropViewport,
): NormalizedCropSquare {
    val rect = cropRectForViewport(imageWidth, imageHeight, transform, viewport)
    val longest = max(imageWidth, imageHeight).toFloat()
    return NormalizedCropSquare(
        left = (rect.left / longest).coerceIn(0f, ((imageWidth - rect.size) / longest).coerceAtLeast(0f)),
        top = (rect.top / longest).coerceIn(0f, ((imageHeight - rect.size) / longest).coerceAtLeast(0f)),
        size = (rect.size / longest).coerceIn(0.001f, 1f),
    )
}

fun cropRectForViewport(
    imageWidth: Int,
    imageHeight: Int,
    transform: CropTransform,
    viewport: IntSize = DefaultCropViewport,
): CropSourceRect {
    val clamped = clampCropTransform(imageWidth, imageHeight, transform, viewport)
    val scale = clamped.scale
    val baseScale = fitScale(imageWidth, imageHeight, viewport)
    val visibleWidth = viewport.width / (baseScale * scale)
    val visibleHeight = viewport.height / (baseScale * scale)
    val cropSizePixels = min(min(visibleWidth, visibleHeight), min(imageWidth, imageHeight).toFloat())
    val centeredLeft = (imageWidth - cropSizePixels) / 2f
    val centeredTop = (imageHeight - cropSizePixels) / 2f
    val left = (centeredLeft - clamped.offsetX / (baseScale * scale)).coerceIn(0f, imageWidth - cropSizePixels)
    val top = (centeredTop - clamped.offsetY / (baseScale * scale)).coerceIn(0f, imageHeight - cropSizePixels)
    return CropSourceRect(left, top, cropSizePixels)
}

private fun fitScale(imageWidth: Int, imageHeight: Int, viewport: IntSize): Float =
    min(viewport.width / imageWidth.toFloat(), viewport.height / imageHeight.toFloat())

private fun minCropScale(imageWidth: Int, imageHeight: Int, viewport: IntSize): Float {
    val fit = fitScale(imageWidth, imageHeight, viewport)
    val cover = max(viewport.width / imageWidth.toFloat(), viewport.height / imageHeight.toFloat())
    return (cover / fit).coerceAtLeast(1f)
}
