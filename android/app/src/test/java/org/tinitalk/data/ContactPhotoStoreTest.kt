package org.tinitalk.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.LruCache
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ContactPhotoStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val address = ContactAddress.of("https://example.com", "Alex")

    @Test
    fun replaceThenLoadReturnsSavedPixels() {
        val store = newStore()

        val stored = store.replace(store.beginReplace(address), solid(Color.RED)).getOrThrow()
        val loaded = store.loadBitmap(address, 64)

        assertTrue(stored.file.exists())
        assertEquals(1L, stored.revision)
        assertNotNull(loaded)
        assertEquals(Color.RED, loaded!!.getPixel(loaded.width / 2, loaded.height / 2))
        assertEquals(1L, store.revision.value)
    }

    @Test
    fun warmCacheThenReplaceWithSameLengthAndTimestampReturnsNewPixels() {
        val redBytes = encodedPng(solid(Color.RED))
        val blueBytes = encodedPng(solid(Color.BLUE)).padTo(redBytes.size)
        val encoder = QueueEncoder(redBytes, blueBytes)
        val store = newStore(encoder = encoder)

        val first = store.replace(store.beginReplace(address), solid(Color.RED)).getOrThrow()
        assertEquals(Color.RED, store.loadBitmap(address, 64)!!.getPixel(10, 10))
        val firstModified = 1_700_000_000_000L
        assertTrue(first.file.setLastModified(firstModified))
        store.replace(store.beginReplace(address), solid(Color.BLUE)).getOrThrow()
        assertTrue(first.file.setLastModified(firstModified))

        val loaded = store.loadBitmap(address, 64)

        assertEquals(Color.BLUE, loaded!!.getPixel(10, 10))
    }

    @Test
    fun decodeThatOverlapsReplacementRetriesAndNeverCachesOldPhoto() {
        val firstDecodeFinished = CountDownLatch(1)
        val allowFirstDecodeToFinish = CountDownLatch(1)
        val decodeCalls = AtomicInteger()
        val store = newStore(
            decoder = { file, _ ->
                BitmapFactory.decodeFile(file.absolutePath).also {
                    if (decodeCalls.incrementAndGet() == 1) {
                        firstDecodeFinished.countDown()
                        check(allowFirstDecodeToFinish.await(5, TimeUnit.SECONDS)) {
                            "first decode was not released"
                        }
                    }
                }
            },
        )
        store.replace(store.beginReplace(address), solid(Color.RED)).getOrThrow()
        val executor = Executors.newSingleThreadExecutor()

        try {
            val loading = executor.submit<Bitmap?> { store.loadBitmap(address, 64) }
            assertTrue(firstDecodeFinished.await(5, TimeUnit.SECONDS))
            store.replace(store.beginReplace(address), solid(Color.BLUE)).getOrThrow()
            allowFirstDecodeToFinish.countDown()

            val loaded = loading.get(5, TimeUnit.SECONDS)

            assertEquals(Color.BLUE, loaded!!.getPixel(loaded.width / 2, loaded.height / 2))
            val cached = store.peekBitmap(address, 64)
            assertEquals(Color.BLUE, cached!!.getPixel(cached.width / 2, cached.height / 2))
            assertTrue(decodeCalls.get() >= 2)
        } finally {
            allowFirstDecodeToFinish.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun secondAccountIdIsIrrelevantForSameAddress() {
        val store = newStore()
        val first = AccountContact(AccountId("one"), "https://example.com", Contact("Alex", "Alex"))
        val second = AccountContact(AccountId("two"), "https://EXAMPLE.com:443/", Contact("Alex", "Alex"))

        store.replace(store.beginReplace(first.address), solid(Color.GREEN)).getOrThrow()

        assertEquals(Color.GREEN, store.loadBitmap(second.address, 64)!!.getPixel(10, 10))
    }

    @Test
    fun differentServersAndLoginCaseUseDifferentFiles() {
        val store = newStore()
        val lower = ContactAddress.of("https://example.com", "alex")
        val upper = ContactAddress.of("https://example.com", "Alex")
        val otherServer = ContactAddress.of("https://other.example", "alex")

        val lowerFile = store.replace(store.beginReplace(lower), solid(Color.RED)).getOrThrow().file
        val upperFile = store.replace(store.beginReplace(upper), solid(Color.GREEN)).getOrThrow().file
        val otherFile = store.replace(store.beginReplace(otherServer), solid(Color.BLUE)).getOrThrow().file

        assertNotEquals(lowerFile.absolutePath, upperFile.absolutePath)
        assertNotEquals(lowerFile.absolutePath, otherFile.absolutePath)
        assertNotEquals(upperFile.absolutePath, otherFile.absolutePath)
    }

    @Test
    fun pathsDoNotContainRawServerOrLogin() {
        val store = newStore()

        val file = store.replace(store.beginReplace(address), solid(Color.RED)).getOrThrow().file

        assertFalse(file.absolutePath.contains("example", ignoreCase = true))
        assertFalse(file.absolutePath.contains("Alex", ignoreCase = true))
    }

    @Test
    fun failedEncodeKeepsPreviousPhoto() {
        val encoder = QueueEncoder(encodedPng(solid(Color.RED)), byteArrayOf(1, 2, 3) to false)
        val store = newStore(encoder = encoder)

        val first = store.replace(store.beginReplace(address), solid(Color.RED)).getOrThrow()
        val failed = store.replace(store.beginReplace(address), solid(Color.BLUE))

        assertTrue(failed.isFailure)
        assertEquals(first.file.absolutePath, store.photo(address)!!.file.absolutePath)
        assertEquals(Color.RED, store.loadBitmap(address, 64)!!.getPixel(10, 10))
        assertEquals(1L, store.revision.value)
    }

    @Test
    fun removePublishesRevisionAndRestoresFallback() {
        val store = newStore()
        store.replace(store.beginReplace(address), solid(Color.RED)).getOrThrow()

        val removed = store.remove(address).getOrThrow()

        assertTrue(removed)
        assertNull(store.photo(address))
        assertNull(store.loadBitmap(address, 64))
        assertEquals(2L, store.revision.value)
    }

    @Test
    fun removeServerMovesWholeNamespaceOutOfResolution() {
        val store = newStore()
        val sameServer = ContactAddress.of("https://example.com", "Beth")
        val otherServer = ContactAddress.of("https://other.example", "Alex")
        store.replace(store.beginReplace(address), solid(Color.RED)).getOrThrow()
        store.replace(store.beginReplace(sameServer), solid(Color.GREEN)).getOrThrow()
        store.replace(store.beginReplace(otherServer), solid(Color.BLUE)).getOrThrow()

        assertTrue(store.removeServer("https://EXAMPLE.com:443/").getOrThrow())

        assertNull(store.photo(address))
        assertNull(store.photo(sameServer))
        assertNotNull(store.photo(otherServer))
        assertEquals(Color.BLUE, store.loadBitmap(otherServer, 64)!!.getPixel(10, 10))
    }

    @Test
    fun staleWriteTokenCannotRestorePhotoAfterServerRemoval() {
        val store = newStore()
        val token = store.beginReplace(address)

        store.removeServer("https://example.com").getOrThrow()
        val result = store.replace(token, solid(Color.RED))

        assertTrue(result.isFailure)
        assertNull(store.photo(address))
    }

    @Test
    fun corruptFileReturnsNullAndDoesNotCrash() {
        val store = newStore()
        val file = store.replace(store.beginReplace(address), solid(Color.RED)).getOrThrow().file
        file.writeBytes(byteArrayOf(1, 2, 3, 4))

        assertNull(store.loadBitmap(address, 64))
    }

    @Test
    fun purgeTrashRemovesInterruptedDeletionArtifacts() {
        val root = temp.newFolder("photos")
        val trashFile = File(root, "v1/.trash/interrupted/file.webp")
        trashFile.parentFile!!.mkdirs()
        trashFile.writeBytes(byteArrayOf(1, 2, 3))
        val store = ContactPhotoStore(root, lru())

        store.purgeTrash().getOrThrow()

        assertFalse(trashFile.exists())
    }

    private fun newStore(
        encoder: ContactPhotoEncoder = WebpContactPhotoEncoder,
        decoder: ((File, Int) -> Bitmap?)? = null,
    ): ContactPhotoStore = ContactPhotoStore(temp.newFolder("photos"), lru(), encoder, decoder)

    private fun lru(): LruCache<String, Bitmap> = object : LruCache<String, Bitmap>(1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private fun solid(color: Int): Bitmap =
        Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    private fun encodedPng(bitmap: Bitmap): ByteArray =
        ByteArrayOutputStream().use { out ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
            out.toByteArray()
        }

    private fun ByteArray.padTo(size: Int): ByteArray =
        if (this.size >= size) this else this + ByteArray(size - this.size)

    private class QueueEncoder(
        vararg entries: Any,
    ) : ContactPhotoEncoder {
        private val writes = ArrayDeque<Pair<ByteArray, Boolean>>().apply {
            entries.forEach { entry ->
                when (entry) {
                    is ByteArray -> add(entry to true)
                    is Pair<*, *> -> add(entry.first as ByteArray to entry.second as Boolean)
                    else -> error("Unsupported encoder entry")
                }
            }
        }

        override fun encode(bitmap: Bitmap, output: OutputStream): Boolean {
            val (bytes, success) = writes.removeFirst()
            output.write(bytes)
            return success
        }
    }
}
