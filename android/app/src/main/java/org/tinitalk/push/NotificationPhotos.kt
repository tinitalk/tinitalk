package org.tinitalk.push

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.DrawableCompat
import org.tinitalk.R
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val NotificationPhotoCornerRadiusFraction = 0.24f
private const val MissedContactPlaceholderPixels = 256
private const val MissedContactPlaceholderBackground = 0xFF0F172A.toInt()
private const val MissedContactPlaceholderForeground = 0xFFD4AF37.toInt()

internal fun roundedNotificationPhoto(source: Bitmap): Bitmap {
    val size = min(source.width, source.height)
    val output = createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val halfSize = size / 2f
    val cornerRadius = size * NotificationPhotoCornerRadiusFraction
    val innerHalfSize = halfSize - cornerRadius
    val left = (source.width - size) / 2
    val top = (source.height - size) / 2
    val row = IntArray(size)
    for (y in 0 until size) {
        source.getPixels(row, 0, size, left, top + y, size, 1)
        for (x in 0 until size) {
            val dx = (abs(x + 0.5f - halfSize) - innerHalfSize).coerceAtLeast(0f)
            val dy = (abs(y + 0.5f - halfSize) - innerHalfSize).coerceAtLeast(0f)
            val coverage = (cornerRadius + 0.5f - sqrt(dx * dx + dy * dy)).coerceIn(0f, 1f)
            val alpha = Color.alpha(row[x])
            row[x] = if (coverage <= 0f || alpha == 0) {
                Color.TRANSPARENT
            } else {
                (row[x] and 0x00FFFFFF) or ((alpha * coverage).roundToInt() shl 24)
            }
        }
        output.setPixels(row, 0, size, 0, y, size, 1)
    }
    return output
}

internal fun missedContactPlaceholder(
    context: Context,
    size: Int = MissedContactPlaceholderPixels,
): Bitmap {
    require(size > 0) { "placeholder size must be positive" }
    val square = createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
        eraseColor(MissedContactPlaceholderBackground)
    }
    val person = DrawableCompat.wrap(
        checkNotNull(ContextCompat.getDrawable(context, R.drawable.ic_person)),
    ).mutate()
    DrawableCompat.setTint(person, MissedContactPlaceholderForeground)
    person.setBounds(0, 0, size, size)
    person.draw(Canvas(square))
    return roundedNotificationPhoto(square).also { square.recycle() }
}
