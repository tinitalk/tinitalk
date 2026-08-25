package org.tinitalk.push

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import org.tinitalk.R
import java.time.Instant

data class IncomingInvite(val callId: String, val caller: String, val expiresAt: Instant)

class IncomingCallNotifier(private val context: Context) {
    fun show(invite: IncomingInvite) {
        ensureChannel()
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, ChannelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        @Suppress("DEPRECATION")
        val notification = builder
            .setSmallIcon(R.drawable.ic_call)
            .setContentTitle("Incoming TiniTalk call")
            .setContentText(invite.caller.ifEmpty { "Incoming call" })
            .setCategory(Notification.CATEGORY_CALL)
            .setPriority(Notification.PRIORITY_HIGH)
            .setOngoing(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(NotificationId, notification)
    }

    fun cancel() {
        context.getSystemService(NotificationManager::class.java).cancel(NotificationId)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(ChannelId, "Incoming calls", NotificationManager.IMPORTANCE_HIGH),
        )
    }

    private companion object {
        const val ChannelId = "incoming_calls"
        const val NotificationId = 11
    }
}

object IncomingPushPayload {
    fun action(data: Map<String, String>): PushAction =
        if (data["type"] == "call_cancel") PushAction.Cancel else PushAction.Show

    fun parse(data: Map<String, String>, now: Instant = Instant.now()): IncomingInvite? {
        if (data["type"] != "incoming_call") return null
        val callId = data["call_id"].orEmpty()
        val expiresAt = runCatching { Instant.parse(data["expires_at"]) }.getOrNull() ?: return null
        if (callId.isEmpty() || !expiresAt.isAfter(now)) return null
        return IncomingInvite(callId, data["caller"].orEmpty(), expiresAt)
    }
}

enum class PushAction {
    Show,
    Cancel,
}
