package org.tinitalk.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val CallAnswerGreen = Color(0xFF21A366)
val CallRejectRed = Color(0xFFE5484D)
val CallBackgroundTop = Color(0xFF14213D)
val CallBackgroundBottom = Color(0xFF07111F)
val BrandBackground = Color(0xFF0F172A)
val BrandGold = Color(0xFFD4AF37)

private val LightColors = lightColorScheme(
    primary = Color(0xFF315EA8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9E6FF),
    onPrimaryContainer = Color(0xFF0D2F60),
    secondary = Color(0xFF42627A),
    background = Color(0xFFF5F7FB),
    surface = Color.White,
    surfaceVariant = Color(0xFFE8EDF5),
    error = CallRejectRed,
)

private val DarkColors = darkColorScheme(
    primary = BrandGold,
    onPrimary = Color(0xFF211B08),
    primaryContainer = Color(0xFF3B3216),
    onPrimaryContainer = Color(0xFFF8E7A4),
    secondary = Color(0xFFC8B978),
    onSecondary = Color(0xFF211D0D),
    secondaryContainer = Color(0xFF39331D),
    onSecondaryContainer = Color(0xFFF2E7BF),
    background = Color(0xFF08111F),
    onBackground = Color(0xFFF1F5F9),
    surface = Color(0xFF151F31),
    onSurface = Color(0xFFF1F5F9),
    surfaceVariant = Color(0xFF1E293B),
    onSurfaceVariant = Color(0xFF94A3B8),
    outline = Color(0xFF475569),
    outlineVariant = Color(0xFF334155),
    error = Color(0xFFFFB3B4),
    errorContainer = Color(0xFF5B2027),
    onErrorContainer = Color(0xFFFFDADB),
)

@Composable
fun TiniTalkTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
