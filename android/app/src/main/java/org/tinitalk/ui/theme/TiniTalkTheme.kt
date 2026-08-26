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
    primary = Color(0xFFAEC7FF),
    onPrimary = Color(0xFF123B73),
    primaryContainer = Color(0xFF244F8D),
    onPrimaryContainer = Color(0xFFD9E6FF),
    secondary = Color(0xFFAAC8E1),
    background = Color(0xFF0E1726),
    surface = Color(0xFF162235),
    surfaceVariant = Color(0xFF27364B),
    error = Color(0xFFFFB3B4),
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
