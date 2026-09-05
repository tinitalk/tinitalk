package org.tinitalk.shortcuts

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.net.Uri
import android.util.Log
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.tinitalk.MainActivity
import org.tinitalk.data.AccountContact
import org.tinitalk.data.AccountId
import org.tinitalk.data.AccountPeerKey
import org.tinitalk.data.AuthStore
import org.tinitalk.data.ContactCache
import org.tinitalk.data.ContactEvents
import org.tinitalk.data.ContactPhotoReader
import org.tinitalk.ui.contactAvatarColors
import org.tinitalk.ui.contactColorIndex
import org.tinitalk.ui.contactInitial

private const val CallShortcutAction = "org.tinitalk.action.CALL_CONTACT_SHORTCUT"
private const val IconSize = 192

private data class ShortcutVisuals(val name: String, val photoGeneration: Int?)

internal fun contactShortcutIntent(context: Context, peer: AccountPeerKey): Intent =
    Intent(context, ShortcutCallActivity::class.java)
        .setAction(CallShortcutAction)
        .setData(Uri.Builder().scheme("tinitalk").authority("shortcut")
            .appendPath("call").appendPath(peer.accountId.value).appendPath(peer.login).build())

internal fun shortcutPeer(intent: Intent?): AccountPeerKey? {
    if (intent?.action != CallShortcutAction) return null
    val uri = intent.data ?: return null
    val path = uri.pathSegments
    if (uri.scheme != "tinitalk" || uri.authority != "shortcut" || path.size != 3 || path[0] != "call") return null
    if (path[1].isBlank() || path[2].isBlank()) return null
    return AccountPeerKey(AccountId(path[1]), path[2])
}

internal class ContactShortcuts(
    context: Context,
    private val photos: ContactPhotoReader,
    private val auth: AuthStore,
    private val cache: ContactCache,
) {
    private val context = context.applicationContext
    private val manager = context.getSystemService(ShortcutManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshRequests = Channel<Unit>(Channel.CONFLATED)
    private val lastVisuals = mutableMapOf<String, ShortcutVisuals>()

    fun observeChanges() {
        ContactEvents.observe { refresh() }
        scope.launch { photos.revision.collect { refresh() } }
        scope.launch {
            for (request in refreshRequests) {
                runCatching { syncPinned() }.onFailure { Log.w("TiniTalkShortcuts", "Could not update pinned shortcuts", it) }
            }
        }
    }

    fun refresh() { refreshRequests.trySend(Unit) }

    // Serialized by the refresh collector. Only changed visuals consume Android's update quota.
    internal fun syncPinned() {
        val manager = manager ?: return
        val pinned = manager.pinnedShortcuts
        lastVisuals.keys.retainAll(pinned.map { it.id }.toSet())
        if (pinned.isEmpty()) return
        val contacts = auth.list().flatMap { cache.load(it).items }.associateBy { it.peerKey }
        val updated = mutableListOf<ShortcutInfo>()
        val visuals = mutableMapOf<String, ShortcutVisuals>()
        pinned.forEach { shortcut ->
            val contact = contacts[shortcutPeer(shortcut.intent)] ?: return@forEach
            val photo = photos.loadBitmap(contact.address, IconSize)
            val current = ShortcutVisuals(contact.displayName, photo?.generationId)
            if (lastVisuals[shortcut.id] != current) {
                updated += create(contact, photo)
                visuals[shortcut.id] = current
            }
        }
        if (updated.isNotEmpty() && manager.updateShortcuts(updated)) lastVisuals.putAll(visuals)
    }

    fun isSupported(): Boolean = manager?.isRequestPinShortcutSupported == true

    // Preparing the bitmap may read a local photo: call this off the UI thread.
    fun create(contact: AccountContact): ShortcutInfo = create(contact, photos.loadBitmap(contact.address, IconSize))

    private fun create(contact: AccountContact, photo: Bitmap?): ShortcutInfo {
        val intent = contactShortcutIntent(context, contact.peerKey)
        return ShortcutInfo.Builder(context, "call:${intent.data}")
            .setActivity(ComponentName(context, MainActivity::class.java))
            .setShortLabel(contact.displayName.ifBlank { contact.login })
            .setLongLabel("Позвонить: ${contact.displayName.ifBlank { contact.login }}")
            .setIcon(contactIcon(contact, photo))
            .setIntent(intent)
            .build()
    }

    fun requestPin(shortcut: ShortcutInfo): Boolean = manager?.requestPinShortcut(shortcut, null) == true

    private fun contactIcon(contact: AccountContact, photo: Bitmap?): Icon {
        val size = IconSize
        val bitmap = createBitmap(size, size)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        if (photo != null) {
            canvas.drawBitmap(photo, null, Rect(0, 0, size, size), paint)
        } else {
            canvas.drawColor(contactAvatarColors[contactColorIndex(contact.login, contactAvatarColors.size)].toArgb())
            paint.color = 0xFFF6E8C0.toInt()
            paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
            // Adaptive icons apply their own mask and inset; keep the letter inside the safe zone.
            paint.textSize = size * 0.30f
            paint.textAlign = Paint.Align.CENTER
            val baseline = size / 2f - (paint.ascent() + paint.descent()) / 2f
            canvas.drawText(contactInitial(contact.displayName, contact.login), size / 2f, baseline, paint)
        }
        return Icon.createWithAdaptiveBitmap(bitmap)
    }
}
