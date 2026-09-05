package org.tinitalk.shortcuts

import android.content.Intent
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.tinitalk.data.AccountId
import org.tinitalk.data.AccountPeerKey
import org.tinitalk.data.AccountContact
import org.tinitalk.data.AccountContactPage
import org.tinitalk.data.AuthStore
import org.tinitalk.data.Contact
import org.tinitalk.data.ContactAddress
import org.tinitalk.data.ContactCache
import org.tinitalk.data.ContactPhotoReader
import org.tinitalk.data.MemoryKeyValueStore
import org.tinitalk.data.PrefixTokenCipher
import org.tinitalk.data.Session

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ContactShortcutsTest {
    @Test
    fun renameAndPhotoChangesUpdateExistingShortcutWithoutTouchingOtherAccount() {
        val context = RuntimeEnvironment.getApplication()
        val store = MemoryKeyValueStore()
        val auth = AuthStore(store, PrefixTokenCipher())
        val cache = ContactCache(store)
        val a = auth.upsert(Session("https://a.example", "me", "a"))
        val b = auth.upsert(Session("https://b.example", "me", "b"))
        val first = AccountContact(a.id, a.session.url, Contact("anna", "Мама"))
        val second = AccountContact(b.id, b.session.url, Contact("anna", "Аня"))
        cache.replace(AccountContactPage(a.id, listOf(first)))
        cache.replace(AccountContactPage(b.id, listOf(second)))
        val photos = TestPhotos()
        val shortcuts = ContactShortcuts(context, photos, auth, cache)
        val manager = context.getSystemService(ShortcutManager::class.java)
        val pinned = shortcuts.create(first)
        manager.requestPinShortcut(pinned, null)
        manager.requestPinShortcut(shortcuts.create(second), null)
        shortcuts.syncPinned()

        cache.updateName(a, first.copy(contact = first.contact.copy(displayName = "Мамуля")))
        shortcuts.syncPinned()
        assertEquals("Мамуля", manager.pinnedShortcuts.single { it.id == pinned.id }.shortLabel)
        assertEquals("Аня", manager.pinnedShortcuts.single { it.id != pinned.id }.shortLabel)
        val beforePhoto = manager.pinnedShortcuts.single { it.id == pinned.id }
        photos.images[first.address] = createBitmap(192, 192)
        shortcuts.syncPinned()
        val withPhoto = manager.pinnedShortcuts.single { it.id == pinned.id }
        assertNotSame(beforePhoto, withPhoto)
        photos.images.remove(first.address)
        shortcuts.syncPinned()
        assertNotSame(withPhoto, manager.pinnedShortcuts.single { it.id == pinned.id })
        assertEquals(2, manager.pinnedShortcuts.size)
    }

    @Test
    fun shortcutKeepsAccountIdentityAndCannotBeChangedUsingExtras() {
        val context = RuntimeEnvironment.getApplication()
        val first = AccountPeerKey(AccountId("first"), "anna")
        val second = AccountPeerKey(AccountId("second"), "anna")
        val intent = contactShortcutIntent(context, first)

        assertEquals(first, shortcutPeer(intent.putExtra("contact_account_id", "second")))
        assertEquals(second, shortcutPeer(contactShortcutIntent(context, second)))
        assertNotEquals(intent.data, contactShortcutIntent(context, second).data)
        assertNull(shortcutPeer(Intent(intent).setAction(Intent.ACTION_VIEW)))
        assertNull(shortcutPeer(Intent(intent).setData(null)))
        assertNull(shortcutPeer(null))
        val activity = context.packageManager.getActivityInfo(intent.component!!, 0)
        assertFalse(activity.exported)
    }
}

private class TestPhotos : ContactPhotoReader {
    override val revision = MutableStateFlow(0L)
    val images = mutableMapOf<ContactAddress, Bitmap>()
    override fun peekBitmap(address: ContactAddress, targetPixels: Int) = images[address]
    override fun loadBitmap(address: ContactAddress, targetPixels: Int) = images[address]
}
