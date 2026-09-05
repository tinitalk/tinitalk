package org.tinitalk.shortcuts

import android.app.PendingIntent
import android.content.BroadcastReceiver
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.tinitalk.MainActivity
import org.tinitalk.R
import org.tinitalk.TinitalkApplication
import org.tinitalk.data.AccountContact
import org.tinitalk.data.AccountId
import org.tinitalk.data.AccountPeerKey
import org.tinitalk.data.AuthStore
import org.tinitalk.data.AuthSessionEvents
import org.tinitalk.data.AuthSessionEvent
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
    private val contactObserver: (AccountId) -> Unit = { refresh() }
    private val accountObserver: (AuthSessionEvent) -> Unit = { refresh() }
    private var closed = false

    fun observeChanges() {
        ContactEvents.observe(contactObserver)
        AuthSessionEvents.observe(accountObserver)
        scope.launch { photos.revision.collect { refresh() } }
        scope.launch {
            for (request in refreshRequests) {
                runCatching { syncPinned() }.onFailure { Log.w("TiniTalkShortcuts", "Could not update pinned shortcuts", it) }
            }
        }
    }

    fun refresh() { refreshRequests.trySend(Unit) }

    fun close() {
        ContactEvents.removeObserver(contactObserver)
        AuthSessionEvents.removeObserver(accountObserver)
        scope.cancel()
        // Wait for any synchronous platform update before the application is torn down.
        synchronized(this) { closed = true }
    }

    // Both the refresh collector and the pin callback use this off the UI thread.
    @Synchronized
    internal fun syncPinned() {
        if (closed) return
        val manager = manager ?: return
        val pinned = manager.pinnedShortcuts
        lastVisuals.keys.retainAll(pinned.map { it.id }.toSet())
        if (pinned.isEmpty()) return
        val accounts = auth.list()
        val contacts = accounts.flatMap { cache.load(it).items }.associateBy { it.peerKey }
        val updated = mutableListOf<ShortcutInfo>()
        val visuals = mutableMapOf<String, ShortcutVisuals>()
        val disabled = mutableMapOf<String, String>()
        val enabled = mutableListOf<String>()
        pinned.forEach { shortcut ->
            val peer = shortcutPeer(shortcut.intent) ?: return@forEach
            val contact = contacts[peer]
            if (contact == null) {
                val hasAccount = accounts.any { it.id == peer.accountId }
                val label = if (hasAccount) "Контакт удалён" else "Нет учётной записи"
                if (shortcut.isEnabled || shortcut.shortLabel.toString() != label) {
                    // Do not leave the deleted personal photo and name on the launcher.
                    updated += ShortcutInfo.Builder(context, shortcut.id)
                        .setActivity(ComponentName(context, MainActivity::class.java))
                        .setIntent(contactShortcutIntent(context, peer))
                        .setShortLabel(label).setLongLabel(label)
                        .setIcon(Icon.createWithResource(context, R.mipmap.ic_launcher))
                        .build()
                }
                if (shortcut.isEnabled) disabled[shortcut.id] = if (hasAccount) {
                    "Контакт удалён из вашей телефонной книги."
                } else {
                    "Вы вышли из учётной записи. Добавьте ярлык заново после входа в TiniTalk."
                }
                lastVisuals.remove(shortcut.id)
                return@forEach
            }
            val photo = photos.loadBitmap(contact.address, IconSize)
            val current = ShortcutVisuals(contact.displayName, photo?.generationId)
            if (!shortcut.isEnabled || lastVisuals[shortcut.id] != current) {
                updated += create(contact, photo)
                visuals[shortcut.id] = current
            }
            if (!shortcut.isEnabled) enabled += shortcut.id
        }
        if (updated.isNotEmpty() && manager.updateShortcuts(updated)) {
            lastVisuals.putAll(visuals)
            if (enabled.isNotEmpty()) manager.enableShortcuts(enabled)
        }
        disabled.entries.groupBy({ it.value }, { it.key }).forEach { (message, ids) ->
            manager.disableShortcuts(ids, message)
        }
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

    fun requestPin(shortcut: ShortcutInfo): Boolean {
        val callback = PendingIntent.getBroadcast(
            context, 0, Intent(context, ShortcutPinnedReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return manager?.requestPinShortcut(shortcut, callback.intentSender) == true
    }

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

/** Recheck after confirmation: the contact may have changed while the launcher dialog was open. */
class ShortcutPinnedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                (context.applicationContext as TinitalkApplication).contactShortcuts.syncPinned()
            } catch (error: Exception) {
                Log.w("TiniTalkShortcuts", "Could not refresh newly pinned shortcut", error)
            } finally {
                pending.finish()
            }
        }
    }
}
