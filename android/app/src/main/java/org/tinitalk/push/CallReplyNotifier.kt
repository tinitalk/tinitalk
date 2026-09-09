package org.tinitalk.push

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Bundle
import org.tinitalk.R
import org.tinitalk.call.CallReplyResult
import org.tinitalk.call.CallScreenVisibility
import org.tinitalk.call.CallSessionBinding
import org.tinitalk.data.AccountId
import org.tinitalk.contactOpenIntent
import org.tinitalk.data.AccountPeerKey

internal class CallReplyNotifier(private val context: Context) {
    fun showUnlessDisplayed(result: CallReplyResult) {
        if (!CallScreenVisibility.isShowing(result.key)) show(result)
    }

    fun show(result: CallReplyResult) {
        val login = result.peer.login ?: return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(Channel, context.getString(R.string.call_reply_result_title), NotificationManager.IMPORTANCE_DEFAULT))
        val intent = contactOpenIntent(context, AccountPeerKey(result.key.accountId, login), result.key.localId())
        val contentIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(context, Channel)
            .setSmallIcon(R.drawable.ic_call)
            .setContentTitle(result.peer.displayName)
            .setContentText(context.getString(result.code.textRes))
            .setStyle(Notification.BigTextStyle().bigText(context.getString(result.code.textRes)))
            .setAutoCancel(true)
            .addExtras(Bundle().apply {
                putString("reply_account", result.key.accountId.value)
                result.sessionBinding?.let {
                    putString("reply_server", it.serverUrl)
                    putString("reply_login", it.login)
                    putString("reply_session", it.sessionId)
                    putString("reply_config", it.configId)
                }
            })
            .setContentIntent(contentIntent)
            .build()
        runCatching { manager.notify(result.key.localId(), NotificationId, notification) }
    }

    fun clearForSession(accountId: AccountId?, binding: CallSessionBinding) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.activeNotifications.filter { it.id == NotificationId }.forEach { active ->
            val extras = active.notification.extras
            if ((accountId == null || extras.getString("reply_account") == accountId.value) &&
                extras.getString("reply_server") == binding.serverUrl && extras.getString("reply_login") == binding.login &&
                extras.getString("reply_session") == binding.sessionId && extras.getString("reply_config") == binding.configId) {
                manager.cancel(active.tag, active.id)
            }
        }
    }

    companion object {
        private const val Channel = "call_replies_v1"
        internal const val NotificationId = 15
    }
}
