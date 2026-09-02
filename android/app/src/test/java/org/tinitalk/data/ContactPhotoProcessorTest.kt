package org.tinitalk.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.media.ExifInterface

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ContactPhotoProcessorTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun copiesContentUriBeforeReturningDraft() {
        val source = temp.newFile("source.png").apply { writeBytes(pngBytes(solid(32, 32, Color.RED))) }
        val processor = processor()

        val draft = processor.importDraft(Uri.fromFile(source)).valueOrThrow()
        source.delete()
        val rendered = processor.render(draft, NormalizedCropSquare(0f, 0f, 1f)).valueOrThrow()

        assertFalse(source.exists())
        assertTrue(draft.sourceFile.exists())
        assertEquals(Color.RED, rendered.getPixel(256, 256))
    }

    @Test
    fun rejectsMoreThan25MiBWhileStreaming() {
        val source = temp.newFile("huge.bin").apply {
            outputStream().use { output ->
                val chunk = ByteArray(1024) { 1 }
                repeat(25 * 1024 + 1) { output.write(chunk) }
            }
        }

        val result = processor().importDraft(Uri.fromFile(source))

        assertEquals(ContactPhotoFailure.TooLarge, result.failureOrNull())
    }

    @Test
    fun rejectsDimensionOver20000Or100MegapixelsBeforeFullDecode() {
        val tooWide = temp.newFile("wide.png").apply { writeBytes(pngHeader(20_001, 1)) }
        val tooManyPixels = temp.newFile("pixels.png").apply { writeBytes(pngHeader(10_001, 10_000)) }

        assertEquals(ContactPhotoFailure.TooLarge, processor().importDraft(Uri.fromFile(tooWide)).failureOrNull())
        assertEquals(ContactPhotoFailure.TooLarge, processor().importDraft(Uri.fromFile(tooManyPixels)).failureOrNull())
    }

    @Test
    fun appliesExifOrientationBeforeCrop() {
        val source = temp.newFile("rotated.jpg").apply {
            writeBytes(jpegBytes(twoByThreeMarker()))
            ExifInterface(absolutePath).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                saveAttributes()
            }
        }

        val draft = processor().importDraft(Uri.fromFile(source)).valueOrThrow()

        assertEquals(120, draft.preview.width)
        assertEquals(80, draft.preview.height)
    }

    @Test
    fun downsamplesPreviewLongestSideToAtMost2048() {
        val source = temp.newFile("large.png").apply { writeBytes(pngBytes(solid(4096, 1024, Color.GREEN))) }

        val draft = processor().importDraft(Uri.fromFile(source)).valueOrThrow()

        assertTrue(draft.preview.width <= ContactPhotoPreviewMaxPixels)
        assertTrue(draft.preview.height <= ContactPhotoPreviewMaxPixels)
    }

    @Test(expected = IllegalArgumentException::class)
    fun normalizedCropMustStayInsideImage() {
        NormalizedCropSquare(0.5f, 0.5f, 0.6f)
    }

    @Test
    fun rendersExactly512By512FromSelectedSquare() {
        val source = temp.newFile("source.png").apply { writeBytes(pngBytes(horizontalHalves())) }
        val draft = processor().importDraft(Uri.fromFile(source)).valueOrThrow()

        val left = processor().render(draft, NormalizedCropSquare(0f, 0f, 0.5f)).valueOrThrow()
        val right = processor().render(draft, NormalizedCropSquare(0.5f, 0f, 0.5f)).valueOrThrow()

        assertEquals(ContactPhotoOutputPixels, left.width)
        assertEquals(ContactPhotoOutputPixels, left.height)
        assertEquals(Color.RED, left.getPixel(256, 256))
        assertEquals(Color.BLUE, right.getPixel(256, 256))
    }

    @Test
    fun renderUsesLongestAxisForCropPositionSoPreviewAndSavedAvatarStayAligned() {
        val source = temp.newFile("source.png").apply { writeBytes(pngBytes(bottomRightMarker())) }
        val draft = processor().importDraft(Uri.fromFile(source)).valueOrThrow()

        val rendered = processor().render(
            draft,
            NormalizedCropSquare(
                left = 115f / 160f,
                top = 45f / 160f,
                size = 45f / 160f,
            ),
        ).valueOrThrow()

        assertEquals(Color.YELLOW, rendered.getPixel(256, 256))
    }

    @Test
    fun animatedInputUsesStaticFirstFrame() {
        val source = temp.newFile("static.gif").apply { writeBytes(minimalGifBytes()) }

        val draft = processor().importDraft(Uri.fromFile(source)).valueOrThrow()

        assertEquals(1, draft.preview.width)
        assertEquals(1, draft.preview.height)
    }

    @Test
    fun discardAndStartupPurgeRemoveDraftFiles() {
        val processor = processor()
        val draft = processor.importDraft(Uri.fromFile(temp.newFile("source.png").apply {
            writeBytes(pngBytes(solid(16, 16, Color.RED)))
        })).valueOrThrow()

        assertTrue(processor.discard(draft))
        assertFalse(draft.sourceFile.exists())

        val leftover = File(context.cacheDir, "contact_photo_drafts/leftover.tmp").apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(1))
        }
        processor.purgeDrafts()
        assertFalse(leftover.exists())
    }

    @Test
    fun renderFailureLeavesTheInputDraftValidForRetry() {
        val processor = processor()
        val draft = processor.importDraft(Uri.fromFile(temp.newFile("source.png").apply {
            writeBytes(pngBytes(solid(16, 16, Color.RED)))
        })).valueOrThrow()
        draft.sourceFile.writeBytes(byteArrayOf(1, 2, 3))

        assertEquals(ContactPhotoFailure.CannotOpen, processor.render(draft, NormalizedCropSquare(0f, 0f, 1f)).failureOrNull())
        assertTrue(draft.sourceFile.exists())
    }

    private fun processor() = ContactPhotoProcessor(context)

    private fun <T> ContactPhotoResult<T>.valueOrThrow(): T =
        (this as ContactPhotoResult.Success<T>).value

    private fun ContactPhotoResult<*>.failureOrNull(): ContactPhotoFailure? =
        (this as? ContactPhotoResult.Failure)?.reason

    private fun solid(width: Int, height: Int, color: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    private fun horizontalHalves(): Bitmap =
        Bitmap.createBitmap(100, 50, Bitmap.Config.ARGB_8888).apply {
            for (x in 0 until width) {
                for (y in 0 until height) {
                    setPixel(x, y, if (x < width / 2) Color.RED else Color.BLUE)
                }
            }
        }

    private fun bottomRightMarker(): Bitmap =
        Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLACK)
            for (x in 115 until width) {
                for (y in 60 until height) {
                    setPixel(x, y, Color.YELLOW)
                }
            }
        }

    private fun twoByThreeMarker(): Bitmap =
        Bitmap.createBitmap(80, 120, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
            for (x in 0 until 30) {
                for (y in 0 until 30) setPixel(x, y, Color.RED)
            }
            for (x in 50 until 80) {
                for (y in 90 until 120) setPixel(x, y, Color.BLUE)
            }
        }

    private fun pngBytes(bitmap: Bitmap): ByteArray =
        ByteArrayOutputStream().use { out ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
            out.toByteArray()
        }

    private fun jpegBytes(bitmap: Bitmap): ByteArray =
        ByteArrayOutputStream().use { out ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out))
            out.toByteArray()
        }

    private fun pngHeader(width: Int, height: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))
        val ihdrData = ByteArrayOutputStream().apply {
            writeInt(width)
            writeInt(height)
            write(byteArrayOf(8, 2, 0, 0, 0))
        }.toByteArray()
        out.writeInt(ihdrData.size)
        out.write("IHDR".toByteArray(Charsets.US_ASCII))
        out.write(ihdrData)
        val crc = CRC32().apply {
            update("IHDR".toByteArray(Charsets.US_ASCII))
            update(ihdrData)
        }.value.toInt()
        out.writeInt(crc)
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.writeInt(value: Int) {
        write((value ushr 24) and 0xff)
        write((value ushr 16) and 0xff)
        write((value ushr 8) and 0xff)
        write(value and 0xff)
    }

    private fun minimalGifBytes(): ByteArray =
        java.util.Base64.getDecoder().decode("R0lGODlhAQABAPAAAP8AAAAAACH5BAAAAAAALAAAAAABAAEAAAICRAEAOw==")

}
