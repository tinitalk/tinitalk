package org.tinitalk.data

import android.graphics.Bitmap
import android.util.LruCache
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.OutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ContactPhotoLifecycleTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun explicitRemovalOfUnownedServerRemovesWholePhotoNamespace() {
        val store = store()
        val lifecycle = ContactPhotoAccountLifecycle(store, isServerOwned = { false })
        val address = ContactAddress.of("https://a.example", "alex")
        store.replace(store.beginReplace(address), bitmap()).getOrThrow()

        lifecycle.removeServerAfterExplicitLogout("HTTPS://A.EXAMPLE:443/")

        assertFalse(store.photo(address)?.file?.exists() == true)
    }

    @Test
    fun explicitRemovalKeepsPhotoNamespaceWhenServerIsStillOwned() {
        val store = store()
        val lifecycle = ContactPhotoAccountLifecycle(store, isServerOwned = { true })
        val address = ContactAddress.of("https://a.example", "alex")
        store.replace(store.beginReplace(address), bitmap()).getOrThrow()

        lifecycle.removeServerAfterExplicitLogout("https://a.example")

        assertTrue(store.photo(address)?.file?.exists() == true)
    }

    @Test
    fun activationAfterCleanupAllowsFreshPhotoWithoutOldNamespaceLeak() {
        val store = store()
        val lifecycle = ContactPhotoAccountLifecycle(store, isServerOwned = { false })
        val address = ContactAddress.of("https://a.example", "alex")
        store.replace(store.beginReplace(address), bitmap()).getOrThrow()
        lifecycle.removeServerAfterExplicitLogout("https://a.example")

        lifecycle.activateServer("https://a.example")
        store.replace(store.beginReplace(address), bitmap()).getOrThrow()

        assertNotNull(store.photo(address))
    }

    private fun store(): ContactPhotoStore =
        ContactPhotoStore(
            temporaryFolder.newFolder(),
            object : LruCache<String, Bitmap>(1024 * 1024) {
                override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
            },
            ContactPhotoEncoder { _, output -> output.writeWebpHeader() },
        )

    private fun bitmap(): Bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)

    private fun OutputStream.writeWebpHeader(): Boolean {
        write(byteArrayOf(0x52, 0x49, 0x46, 0x46, 0, 0, 0, 0, 0x57, 0x45, 0x42, 0x50))
        return true
    }
}
