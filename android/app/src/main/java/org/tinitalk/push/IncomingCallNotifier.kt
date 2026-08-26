package org.tinitalk.push

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Person
import android.content.Context
import android.os.Build
import org.tinitalk.R
import org.tinitalk.call.CallPhase
import org.tinitalk.call.CallSnapshot
import org.tinitalk.telecom.IncomingCallController
import java.time.Instant
import java.time.Duration

data class IncomingInvite(
    val callId: String,
    val caller: String,
    val expiresAt: Instant,
    val lastSeq: Long = 0,
)

class IncomingCallNotifier(private val context: Context) {
    fun show(invite: IncomingInvite) {
        ensureChannel()
        val controller = IncomingCallController()
        if (controller.isTerminal(context, invite.callId)) return
        controller.save(context, invite)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, ChannelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        val answer = controller.actionIntent(context, IncomingCallController.ActionAnswer, invite)
        val reject = controller.actionIntent(context, IncomingCallController.ActionReject, invite)
        val fullScreen = controller.activityIntent(context, IncomingCallController.ActionIncoming, invite)
        @Suppress("DEPRECATION")
        builder
            .setSmallIcon(R.drawable.ic_call)
            .setContentTitle("Incoming TiniTalk call")
            .setContentText(invite.caller.ifEmpty { "Incoming call" })
            .setCategory(Notification.CATEGORY_CALL)
            .setPriority(Notification.PRIORITY_HIGH)
            .setContentIntent(fullScreen)
            .setFullScreenIntent(fullScreen, canUseFullScreenIntent())
            .setOngoing(true)
            .setTimeoutAfter(Duration.between(Instant.now(), invite.expiresAt).toMillis().coerceAtLeast(0))
        if (Build.VERSION.SDK_INT >= 31) {
            builder.setStyle(
                Notification.CallStyle.forIncomingCall(
                    Person.Builder().setName(invite.caller.ifEmpty { "TiniTalk" }).setImportant(true).build(),
                    reject,
                    answer,
                ),
            )
        } else {
            builder
                .addAction(Notification.Action.Builder(R.drawable.ic_call, "Reject", reject).build())
                .addAction(Notification.Action.Builder(R.drawable.ic_call, "Answer", answer).build())
        }
        val notification = builder.build()
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

    private fun canUseFullScreenIntent(): Boolean {
        if (Build.VERSION.SDK_INT < 34) return true
        return context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
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
        return IncomingInvite(
            callId = callId,
            caller = data["caller"].orEmpty(),
            expiresAt = expiresAt,
            lastSeq = data["last_seq"]?.toLongOrNull() ?: 0,
        )
    }

    fun cancellation(data: Map<String, String>): CallCancellation? {
        if (data["type"] != "call_cancel") return null
        val callId = data["call_id"].orEmpty()
        if (callId.isEmpty()) return null
        return CallCancellation(callId, data["call_event"].orEmpty())
    }
}

data class CallCancellation(val callId: String, val eventType: String) {
    fun shouldDismiss(pendingCallId: String?, snapshot: CallSnapshot): Boolean {
        if (pendingCallId != callId) return false
        return eventType != "call.accept" ||
            snapshot.callId != callId || snapshot.phase != CallPhase.Active
    }
}

enum class PushAction {
    Show,
    Cancel,
}
