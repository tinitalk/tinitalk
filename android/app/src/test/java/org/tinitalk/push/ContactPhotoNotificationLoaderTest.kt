package org.tinitalk.push

import android.graphics.Bitmap
import org.tinitalk.data.ContactAddress
import org.tinitalk.data.ContactPhotoReader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ContactPhotoNotificationLoaderTest {
    private val address = ContactAddress.of("https://example.com", "alex")

    @Test
    fun peekUsesOnlyMemoryCacheAtNotificationSize() {
        val reader = RecordingReader()
        val loader = ContactPhotoNotificationLoader(reader) { it.run() }

        val bitmap = loader.peek(address)

        assertSame(reader.bitmap, bitmap)
        assertEquals(listOf(256), reader.peekSizes)
        assertEquals(emptyList<Int>(), reader.loadSizes)
    }

    @Test
    fun loadUsesNotificationSizeAndCarriesRequestRevision() {
        val reader = RecordingReader()
        val loader = ContactPhotoNotificationLoader(reader) { it.run() }
        var callback: Triple<String, Long, Bitmap?>? = null

        loader.load(address, "call-1", 7L) { requestKey, revision, bitmap ->
            callback = Triple(requestKey, revision, bitmap)
        }

        assertEquals(listOf(256), reader.loadSizes)
        assertEquals(Triple("call-1", 7L, reader.bitmap), callback)
    }

    @Test
    fun concurrentSameAddressAndRevisionIsDeduplicated() {
        val reader = RecordingReader()
        val queued = mutableListOf<Runnable>()
        val loader = ContactPhotoNotificationLoader(reader) { queued += it }
        val callbacks = mutableListOf<String>()

        loader.load(address, "call-1", 3L) { requestKey, _, _ -> callbacks += requestKey }
        loader.load(address, "call-2", 3L) { requestKey, _, _ -> callbacks += requestKey }
        assertEquals(1, queued.size)

        queued.single().run()

        assertEquals(listOf(256), reader.loadSizes)
        assertEquals(listOf("call-1", "call-2"), callbacks)
    }

    private class RecordingReader : ContactPhotoReader {
        val bitmap: Bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val revisionFlow = MutableStateFlow(0L)
        val peekSizes = mutableListOf<Int>()
        val loadSizes = mutableListOf<Int>()
        override val revision: StateFlow<Long> = revisionFlow
        override fun peekBitmap(address: ContactAddress, targetPixels: Int): Bitmap? {
            peekSizes += targetPixels
            return bitmap
        }
        override fun loadBitmap(address: ContactAddress, targetPixels: Int): Bitmap? {
            loadSizes += targetPixels
            return bitmap
        }
    }
}
