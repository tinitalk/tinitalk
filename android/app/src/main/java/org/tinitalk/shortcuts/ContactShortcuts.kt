package org.tinitalk.shortcuts

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.net.Uri
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.createBitmap
import org.tinitalk.MainActivity
import org.tinitalk.data.AccountContact
import org.tinitalk.data.AccountId
import org.tinitalk.data.AccountPeerKey
import org.tinitalk.data.ContactPhotoReader
import org.tinitalk.ui.contactAvatarColors
import org.tinitalk.ui.contactColorIndex
import org.tinitalk.ui.contactInitial

private const val CallShortcutAction = "org.tinitalk.action.CALL_CONTACT_SHORTCUT"

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

internal class ContactShortcuts(context: Context, private val photos: ContactPhotoReader) {
    private val context = context.applicationContext
    private val manager = context.getSystemService(ShortcutManager::class.java)

    fun isSupported(): Boolean = manager?.isRequestPinShortcutSupported == true

    // Preparing the bitmap may read a local photo: call this off the UI thread.
    fun create(contact: AccountContact): ShortcutInfo {
        val intent = contactShortcutIntent(context, contact.peerKey)
        return ShortcutInfo.Builder(context, "call:${intent.data}")
            .setActivity(ComponentName(context, MainActivity::class.java))
            .setShortLabel(contact.displayName.ifBlank { contact.login })
            .setLongLabel("Позвонить: ${contact.displayName.ifBlank { contact.login }}")
            .setIcon(contactIcon(contact))
            .setIntent(intent)
            .build()
    }

    fun requestPin(shortcut: ShortcutInfo): Boolean = manager?.requestPinShortcut(shortcut, null) == true

    private fun contactIcon(contact: AccountContact): Icon {
        val size = 192
        val bitmap = createBitmap(size, size)
        val canvas = Canvas(bitmap)
        val photo = photos.loadBitmap(contact.address, size)
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
