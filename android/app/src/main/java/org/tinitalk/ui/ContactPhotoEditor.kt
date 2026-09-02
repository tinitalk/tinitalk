package org.tinitalk.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.tinitalk.data.NormalizedCropSquare
import kotlin.math.max
import kotlin.math.min

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
    var transform by remember(draft.id) { mutableStateOf(defaultCropTransform(draft.preview)) }
    val crop = normalizedCropForViewport(draft.preview.width, draft.preview.height, transform)

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
                        .clip(CircleShape)
                        .background(Color.Black)
                        .pointerInput(draft.id) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                transform = clampCropTransform(
                                    transform.copy(
                                        offsetX = transform.offsetX + pan.x,
                                        offsetY = transform.offsetY + pan.y,
                                        scale = transform.scale * zoom,
                                    ),
                                )
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        bitmap = draft.preview.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = transform.scale
                                scaleY = transform.scale
                                translationX = transform.offsetX
                                translationY = transform.offsetY
                            },
                    )
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

fun defaultCropTransform(bitmap: Bitmap): CropTransform =
    CropTransform(scale = if (bitmap.width == bitmap.height) 1f else 1.15f, offsetX = 0f, offsetY = 0f)

fun clampCropTransform(transform: CropTransform): CropTransform =
    transform.copy(
        scale = transform.scale.coerceIn(1f, 4f),
        offsetX = transform.offsetX.coerceIn(-220f, 220f),
        offsetY = transform.offsetY.coerceIn(-220f, 220f),
    )

fun normalizedCropForViewport(
    imageWidth: Int,
    imageHeight: Int,
    transform: CropTransform,
    viewport: IntSize = IntSize(280, 280),
): NormalizedCropSquare {
    val scale = transform.scale.coerceIn(1f, 4f)
    val longest = max(imageWidth, imageHeight).toFloat()
    val baseScale = min(viewport.width / imageWidth.toFloat(), viewport.height / imageHeight.toFloat())
    val visibleWidth = viewport.width / (baseScale * scale)
    val visibleHeight = viewport.height / (baseScale * scale)
    val cropSizePixels = min(min(visibleWidth, visibleHeight), min(imageWidth, imageHeight).toFloat())
    val centeredLeft = (imageWidth - cropSizePixels) / 2f
    val centeredTop = (imageHeight - cropSizePixels) / 2f
    val left = (centeredLeft - transform.offsetX / (baseScale * scale)).coerceIn(0f, imageWidth - cropSizePixels)
    val top = (centeredTop - transform.offsetY / (baseScale * scale)).coerceIn(0f, imageHeight - cropSizePixels)
    val size = (cropSizePixels / longest).coerceIn(0.001f, 1f)
    return NormalizedCropSquare(
        left = (left / imageWidth).coerceIn(0f, 1f - size),
        top = (top / imageHeight).coerceIn(0f, 1f - size),
        size = size,
    )
}
