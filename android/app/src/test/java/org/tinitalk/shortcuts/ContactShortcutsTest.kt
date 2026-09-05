package org.tinitalk.shortcuts

import android.content.Intent
import android.content.pm.ShortcutManager
import android.content.pm.ShortcutInfo
import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
import org.robolectric.shadows.ShadowShortcutManager
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
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
@Config(sdk = [35], application = android.app.Application::class, shadows = [ShortcutEnabledStateShadow::class])
class ContactShortcutsTest {
    @Test
    fun removalDisablesOnlyRelatedShortcutsButCallAvailabilityDoesNot() {
        val context = RuntimeEnvironment.getApplication()
        val store = MemoryKeyValueStore()
        val auth = AuthStore(store, PrefixTokenCipher())
        val cache = ContactCache(store)
        val a = auth.upsert(Session("https://a.example", "me", "a"))
        val b = auth.upsert(Session("https://b.example", "me", "b"))
        val first = AccountContact(a.id, a.session.url, Contact("anna", "Мама"))
        val second = AccountContact(b.id, b.session.url, Contact("anna", "Аня", canCall = false))
        cache.replace(AccountContactPage(a.id, listOf(first)))
        cache.replace(AccountContactPage(b.id, listOf(second)))
        val shortcuts = ContactShortcuts(context, TestPhotos(), auth, cache)
        val manager = context.getSystemService(ShortcutManager::class.java)
        val firstId = shortcuts.create(first).also { manager.requestPinShortcut(it, null) }.id
        val secondId = shortcuts.create(second).also { manager.requestPinShortcut(it, null) }.id

        cache.remove(a, "anna")
        shortcuts.syncPinned()
        assertFalse(manager.pinnedShortcuts.single { it.id == firstId }.isEnabled)
        assertEquals("Контакт удалён", manager.pinnedShortcuts.single { it.id == firstId }.shortLabel)
        assertTrue(manager.pinnedShortcuts.single { it.id == secondId }.isEnabled)
        cache.update(a, first.copy(contact = first.contact.copy(displayName = "Мамуля")))
        shortcuts.syncPinned()
        assertTrue(manager.pinnedShortcuts.single { it.id == firstId }.isEnabled)
        assertEquals("Мамуля", manager.pinnedShortcuts.single { it.id == firstId }.shortLabel)
        auth.remove(b.id)
        shortcuts.syncPinned()
        assertFalse(manager.pinnedShortcuts.single { it.id == secondId }.isEnabled)
        assertEquals("Нет учётной записи", manager.pinnedShortcuts.single { it.id == secondId }.shortLabel)
        assertTrue(manager.pinnedShortcuts.single { it.id == firstId }.isEnabled)
    }

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

// Robolectric 4.16 moves disabled shortcuts between maps but does not update isEnabled.
@Implements(ShortcutManager::class)
class ShortcutEnabledStateShadow : ShadowShortcutManager() {
    @Implementation
    override fun disableShortcuts(ids: List<String>, message: CharSequence) {
        changeEnabledFlag(ids, "addFlags")
        super.disableShortcuts(ids, message)
    }

    @Implementation
    override fun enableShortcuts(ids: List<String>) {
        changeEnabledFlag(ids, "clearFlags")
        super.enableShortcuts(ids)
    }

    private fun changeEnabledFlag(ids: List<String>, method: String) {
        val disabled: Int = ReflectionHelpers.getStaticField(ShortcutInfo::class.java, "FLAG_DISABLED")
        pinnedShortcuts.filter { it.id in ids }.forEach {
            ReflectionHelpers.callInstanceMethod<Void>(it, method, ClassParameter.from(Int::class.javaPrimitiveType, disabled))
        }
    }
}
