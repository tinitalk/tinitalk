package org.tinitalk.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.core.graphics.scale
import androidx.exifinterface.media.ExifInterface
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

internal const val ContactPhotoMaxBytes = 25L * 1024L * 1024L
internal const val ContactPhotoMaxSidePixels = 20_000
internal const val ContactPhotoMaxPixels = 100_000_000L
internal const val ContactPhotoPreviewMaxPixels = 2048
internal const val ContactPhotoOutputPixels = 512

data class NormalizedCropSquare(
    val left: Float,
    val top: Float,
    val size: Float,
) {
    init {
        require(size > 0f)
        require(left >= 0f && top >= 0f)
        require(left + size <= 1f)
        require(top + size <= 1f)
    }
}

class ContactPhotoDraft internal constructor(
    val id: String,
    internal val sourceFile: File,
    val preview: Bitmap,
)

enum class ContactPhotoFailure {
    CannotOpen,
    TooLarge,
    NoSpace,
    CannotSave,
}

sealed interface ContactPhotoResult<out T> {
    data class Success<T>(val value: T) : ContactPhotoResult<T>
    data class Failure(val reason: ContactPhotoFailure, val cause: Throwable? = null) : ContactPhotoResult<Nothing>
}

class ContactPhotoProcessor(context: Context) {
    private val appContext = context.applicationContext
    private val draftDir: File
        get() = File(appContext.cacheDir, "contact_photo_drafts")

    fun importDraft(uri: Uri): ContactPhotoResult<ContactPhotoDraft> {
        val id = UUID.randomUUID().toString()
        val destination = File(draftDir, "$id.img")
        return try {
            draftDir.mkdirs()
            copyBounded(uri, destination)
            val dimensions = readDimensions(destination)
                ?: return destination.deleteAndFail(ContactPhotoFailure.CannotOpen)
            if (dimensions.tooLarge()) {
                return destination.deleteAndFail(ContactPhotoFailure.TooLarge)
            }
            val preview = decodeOriented(destination, ContactPhotoPreviewMaxPixels)
                ?: return destination.deleteAndFail(ContactPhotoFailure.CannotOpen)
            ContactPhotoResult.Success(ContactPhotoDraft(id, destination, preview))
        } catch (tooLarge: PhotoTooLargeException) {
            destination.delete()
            ContactPhotoResult.Failure(ContactPhotoFailure.TooLarge, tooLarge)
        } catch (noSpace: IOException) {
            destination.delete()
            val reason = if (noSpace.message?.contains("space", ignoreCase = true) == true) {
                ContactPhotoFailure.NoSpace
            } else {
                ContactPhotoFailure.CannotOpen
            }
            ContactPhotoResult.Failure(reason, noSpace)
        } catch (throwable: Throwable) {
            destination.delete()
            ContactPhotoResult.Failure(ContactPhotoFailure.CannotOpen, throwable)
        }
    }

    fun render(draft: ContactPhotoDraft, crop: NormalizedCropSquare): ContactPhotoResult<Bitmap> =
        try {
            val bitmap = decodeOriented(draft.sourceFile, Int.MAX_VALUE)
                ?: return ContactPhotoResult.Failure(ContactPhotoFailure.CannotOpen)
            val longest = max(bitmap.width, bitmap.height)
            val size = (longest * crop.size).roundToInt()
                .coerceIn(1, minOf(bitmap.width, bitmap.height))
            val left = (longest * crop.left).roundToInt().coerceIn(0, bitmap.width - size)
            val top = (longest * crop.top).roundToInt().coerceIn(0, bitmap.height - size)
            val cropped = Bitmap.createBitmap(bitmap, left, top, size, size)
            val scaled = cropped.scale(ContactPhotoOutputPixels, ContactPhotoOutputPixels, filter = true)
            if (cropped !== scaled) cropped.recycle()
            if (bitmap !== cropped && bitmap !== scaled) bitmap.recycle()
            ContactPhotoResult.Success(scaled)
        } catch (throwable: Throwable) {
            ContactPhotoResult.Failure(ContactPhotoFailure.CannotOpen, throwable)
        }

    fun discard(draft: ContactPhotoDraft): Boolean = draft.sourceFile.delete()

    fun purgeDrafts() {
        if (draftDir.exists()) {
            draftDir.deleteRecursively()
        }
        draftDir.mkdirs()
    }

    private fun copyBounded(uri: Uri, destination: File) {
        appContext.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
            val declaredLength = descriptor.length
            if (declaredLength > ContactPhotoMaxBytes) throw PhotoTooLargeException()
        }
        val input = appContext.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open image")
        input.use { source ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var copied = 0L
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    if (copied + read > ContactPhotoMaxBytes) throw PhotoTooLargeException()
                    output.write(buffer, 0, read)
                    copied += read
                }
                output.fd.sync()
            }
        }
    }

    private fun readDimensions(file: File): ImageDimensions? =
        readPngDimensions(file) ?: bitmapFactoryDimensions(file)

    private fun bitmapFactoryDimensions(file: File): ImageDimensions? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        return if (options.outWidth > 0 && options.outHeight > 0) {
            ImageDimensions(options.outWidth, options.outHeight)
        } else {
            null
        }
    }

    private fun readPngDimensions(file: File): ImageDimensions? {
        val header = ByteArray(24)
        val read = file.inputStream().use { it.read(header) }
        if (read < header.size) return null
        val isPng = header[0] == 0x89.toByte() &&
            header[1] == 0x50.toByte() &&
            header[2] == 0x4e.toByte() &&
            header[3] == 0x47.toByte()
        if (!isPng) return null
        return ImageDimensions(header.readInt(16), header.readInt(20))
    }

    private fun ByteArray.readInt(offset: Int): Int =
        ((this[offset].toInt() and 0xff) shl 24) or
            ((this[offset + 1].toInt() and 0xff) shl 16) or
            ((this[offset + 2].toInt() and 0xff) shl 8) or
            (this[offset + 3].toInt() and 0xff)

    private fun decodeOriented(file: File, maxLongestSide: Int): Bitmap? {
        if (!hasSupportedImageHeader(file)) return null
        val dimensions = readDimensions(file) ?: return null
        val sample = sampleSize(dimensions.width, dimensions.height, maxLongestSide)
        val decoded = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null
        val matrix = orientationMatrix(file)
        return if (matrix.isIdentity) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also {
            if (it !== decoded) decoded.recycle()
        }
    }

    private fun sampleSize(width: Int, height: Int, maxLongestSide: Int): Int {
        if (maxLongestSide == Int.MAX_VALUE) return 1
        var sample = 1
        while (max(width, height) / (sample * 2) >= maxLongestSide) {
            sample *= 2
        }
        return sample
    }

    private fun hasSupportedImageHeader(file: File): Boolean {
        val header = ByteArray(12)
        val read = file.inputStream().use { input -> input.read(header) }
        if (read < 4) return false
        val png = header[0] == 0x89.toByte() &&
            header[1] == 0x50.toByte() &&
            header[2] == 0x4e.toByte() &&
            header[3] == 0x47.toByte()
        val jpeg = header[0] == 0xff.toByte() && header[1] == 0xd8.toByte()
        val gif = header[0] == 'G'.code.toByte() &&
            header[1] == 'I'.code.toByte() &&
            header[2] == 'F'.code.toByte()
        val webp = read >= 12 &&
            header[0] == 'R'.code.toByte() &&
            header[1] == 'I'.code.toByte() &&
            header[2] == 'F'.code.toByte() &&
            header[3] == 'F'.code.toByte() &&
            header[8] == 'W'.code.toByte() &&
            header[9] == 'E'.code.toByte() &&
            header[10] == 'B'.code.toByte() &&
            header[11] == 'P'.code.toByte()
        return png || jpeg || gif || webp
    }

    private fun orientationMatrix(file: File): Matrix {
        val orientation = runCatching {
            ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        return Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> preScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> preScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    postRotate(90f)
                    preScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    postRotate(-90f)
                    preScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(-90f)
            }
        }
    }

    private fun File.deleteAndFail(reason: ContactPhotoFailure): ContactPhotoResult.Failure {
        delete()
        return ContactPhotoResult.Failure(reason)
    }

    private fun ImageDimensions.tooLarge(): Boolean =
        width > ContactPhotoMaxSidePixels ||
            height > ContactPhotoMaxSidePixels ||
            width.toLong() * height.toLong() > ContactPhotoMaxPixels

    private data class ImageDimensions(val width: Int, val height: Int)

    private class PhotoTooLargeException : IOException("Image is too large")
}
