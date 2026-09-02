package org.tinitalk.ui

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.tinitalk.data.ContactAddress
import org.tinitalk.data.ContactPhotoReader
import org.tinitalk.ui.theme.BrandGold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import androidx.compose.runtime.LaunchedEffect

internal val contactAvatarColors = listOf(
    Color(0xFF394A67),
    Color(0xFF514464),
    Color(0xFF30514D),
    Color(0xFF60443B),
    Color(0xFF4E5337),
    Color(0xFF593F4C),
)

val LocalContactPhotoReader: ProvidableCompositionLocal<ContactPhotoReader> =
    compositionLocalOf { NoContactPhotoReader }

private object NoContactPhotoReader : ContactPhotoReader {
    override val revision: StateFlow<Long> = MutableStateFlow(0L)
    override fun peekBitmap(address: ContactAddress, targetPixels: Int): Bitmap? = null
    override fun loadBitmap(address: ContactAddress, targetPixels: Int): Bitmap? = null
}

@Composable
fun ContactAvatar(
    address: ContactAddress?,
    displayName: String,
    fallbackLogin: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    borderWidth: Dp = 1.dp,
    shadowElevation: Dp = 0.dp,
    pulsingScale: Float = 1f,
    contentDescription: String? = null,
) {
    val reader = LocalContactPhotoReader.current
    val revision by reader.revision.collectAsState()
    val density = LocalDensity.current
    val targetPixels = remember(size, density) {
        with(density) { size.roundToPx().coerceAtMost(512).coerceAtLeast(1) }
    }
    var bitmap by remember(address, targetPixels) {
        mutableStateOf(address?.let { reader.peekBitmap(it, targetPixels) })
    }

    LaunchedEffect(address, revision, targetPixels, reader) {
        bitmap = address?.let { current ->
            withContext(Dispatchers.IO) { reader.loadBitmap(current, targetPixels) }
        }
    }

    val image = bitmap
    if (image != null) {
        Image(
            bitmap = image.asImageBitmap(),
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = modifier
                .size(size)
                .scale(pulsingScale)
                .clip(CircleShape)
                .testTag("contact-avatar-photo"),
        )
        return
    }

    val color = contactAvatarColors[contactColorIndex(fallbackLogin.orEmpty(), contactAvatarColors.size)]
    Surface(
        modifier = modifier.size(size).scale(pulsingScale),
        shape = CircleShape,
        color = color,
        border = BorderStroke(borderWidth, BrandGold.copy(alpha = 0.42f)),
        shadowElevation = shadowElevation,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                contactInitial(displayName, fallbackLogin.orEmpty()),
                color = Color(0xFFF6E8C0),
                fontSize = if (size >= 96.dp) 40.sp else 20.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
