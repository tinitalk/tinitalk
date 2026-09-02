package org.tinitalk.ui

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.tinitalk.data.ContactAddress
import org.tinitalk.data.ContactPhotoReader
import org.tinitalk.ui.theme.BrandGold
import java.util.concurrent.atomic.AtomicInteger
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

private const val ContactAvatarInitialSizeFraction = 0.38f
private const val ContactAvatarMinimumInitialSizeDp = 20f

internal fun contactAvatarInitialFontSizeSp(avatarSizeDp: Float, fontScale: Float): Float =
    maxOf(ContactAvatarMinimumInitialSizeDp, avatarSizeDp * ContactAvatarInitialSizeFraction) /
        fontScale.coerceAtLeast(0.1f)

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
    showRefreshProgress: Boolean = false,
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
    var refreshing by remember(address, targetPixels, reader) { mutableStateOf(false) }
    val loadGeneration = remember(address, targetPixels, reader) { AtomicInteger() }

    LaunchedEffect(address, revision, targetPixels, reader) {
        val generation = loadGeneration.incrementAndGet()
        val current = address
        if (current == null) {
            bitmap = null
            refreshing = false
            return@LaunchedEffect
        }
        val cached = reader.peekBitmap(current, targetPixels)
        if (cached != null) {
            bitmap = cached
            refreshing = false
            return@LaunchedEffect
        }
        refreshing = showRefreshProgress && bitmap != null
        try {
            val loaded = withContext(Dispatchers.IO) { reader.loadBitmap(current, targetPixels) }
            if (loadGeneration.get() == generation) bitmap = loaded
        } finally {
            if (loadGeneration.get() == generation) refreshing = false
        }
    }

    val image = bitmap
    Box(
        modifier = modifier.size(size).scale(pulsingScale),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .testTag("contact-avatar-photo"),
            )
        } else {
            val color = contactAvatarColors[contactColorIndex(fallbackLogin.orEmpty(), contactAvatarColors.size)]
            Surface(
                modifier = Modifier.fillMaxSize(),
                shape = CircleShape,
                color = color,
                border = BorderStroke(borderWidth, BrandGold.copy(alpha = 0.42f)),
                shadowElevation = shadowElevation,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        contactInitial(displayName, fallbackLogin.orEmpty()),
                        color = Color(0xFFF6E8C0),
                        fontSize = contactAvatarInitialFontSizeSp(size.value, density.fontScale).sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
        if (refreshing) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.46f))
                    .semantics(mergeDescendants = true) { this.contentDescription = "Обновление фото" }
                    .testTag("contact-avatar-refresh-overlay"),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(44.dp).testTag("contact-avatar-refresh-progress"),
                    color = BrandGold,
                    strokeWidth = 4.dp,
                )
            }
        }
    }
}
