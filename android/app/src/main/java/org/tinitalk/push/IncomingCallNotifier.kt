package org.tinitalk.push

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Person
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import org.tinitalk.CallActivity
import org.tinitalk.MainActivity
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
    val callerLogin: String? = null,
    val lastSeq: Long = 0,
)

internal class IncomingCallForegroundPresentation(
    private val enterForeground: (IncomingInvite) -> Unit,
    private val openFullScreen: (IncomingInvite) -> Unit,
) {
    fun present(invite: IncomingInvite) {
        enterForeground(invite)
        openFullScreen(invite)
    }
}

internal class IncomingCallAlertHandoff(
    private val startRingtone: (IncomingInvite) -> Unit,
    private val dismissNotification: () -> Unit,
) {
    fun fullScreenShown(invite: IncomingInvite) {
        startRingtone(invite)
        dismissNotification()
    }
}

private object IncomingRingtone {
    private var callId: String? = null
    private var ringtone: Ringtone? = null

    @Synchronized
    fun start(context: Context, invite: IncomingInvite) {
        if (callId == invite.callId && ringtone?.isPlaying == true) return
        stop()

        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE) ?: return
        val next = RingtoneManager.getRingtone(context.applicationContext, uri) ?: return
        next.audioAttributes = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) next.isLooping = true

        callId = invite.callId
        ringtone = next
        runCatching { next.play() }.onFailure { stop(invite.callId) }
    }

    @Synchronized
    fun stop(expectedCallId: String? = null) {
        if (expectedCallId != null && callId != expectedCallId) return
        runCatching { ringtone?.stop() }
        ringtone = null
        callId = null
    }
}

class IncomingCallNotifier(private val context: Context) {
    fun show(invite: IncomingInvite) {
        val notification = buildIncomingNotification(invite) ?: return
        context.getSystemService(NotificationManager::class.java).notify(NotificationId, notification)
    }

    internal fun buildIncomingNotification(invite: IncomingInvite): Notification? {
        ensureChannel()
        val controller = IncomingCallController()
        if (controller.isTerminal(context, invite.callId)) return null
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
            .setContentTitle("Входящий звонок")
            .setContentText(invite.caller.ifEmpty { "TiniTalk" })
            .setCategory(Notification.CATEGORY_CALL)
            .setPriority(Notification.PRIORITY_HIGH)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
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
                .addAction(Notification.Action.Builder(R.drawable.ic_call, "Отклонить", reject).build())
                .addAction(Notification.Action.Builder(R.drawable.ic_call, "Ответить", answer).build())
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            builder
                .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
                .setVibrate(longArrayOf(0, 500, 500, 500))
        }
        return builder.build()
    }

    fun showMissed(invite: IncomingInvite) {
        ensureMissedChannel()
        val manager = context.getSystemService(NotificationManager::class.java)
        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, MissedChannelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        builder
            .setSmallIcon(R.drawable.ic_call)
            .setContentTitle("Пропущенный звонок")
            .setContentText(invite.caller.ifEmpty { "TiniTalk" })
            .setCategory(Notification.CATEGORY_MISSED_CALL)
            .setContentIntent(openApp)
            .setAutoCancel(true)
        invite.callerLogin?.takeIf(String::isNotBlank)?.let { login ->
            val redial = PendingIntent.getActivity(
                context,
                invite.callId.hashCode(),
                CallActivity.redialIntent(context, login, invite.caller.ifBlank { login }),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(Notification.Action.Builder(R.drawable.ic_call, "Перезвонить", redial).build())
        }
        manager.notify(MissedNotificationId, builder.build())
    }

    fun cancel() {
        dismissNotification()
        IncomingRingtone.stop()
    }

    fun fullScreenShown(invite: IncomingInvite) {
        IncomingCallAlertHandoff(
            startRingtone = { IncomingRingtone.start(context, it) },
            dismissNotification = ::dismissNotification,
        ).fullScreenShown(invite)
    }

    fun fullScreenHidden(invite: IncomingInvite) {
        IncomingRingtone.stop(invite.callId)
        show(invite)
    }

    private fun dismissNotification() {
        IncomingCallForegroundService.stop(context)
        context.getSystemService(NotificationManager::class.java).cancel(NotificationId)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val ringtone = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val audio = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .build()
        val channel = NotificationChannel(ChannelId, "Входящие звонки", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Звонок и вибрация для входящих вызовов"
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            enableVibration(true)
            setSound(ringtone, audio)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun ensureMissedChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(MissedChannelId, "Пропущенные звонки", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    private fun canUseFullScreenIntent(): Boolean {
        if (Build.VERSION.SDK_INT < 34) return true
        return context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
    }

    companion object {
        private const val ChannelId = "incoming_calls_v2"
        private const val MissedChannelId = "missed_calls"
        internal const val NotificationId = 11
        private const val MissedNotificationId = 12
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
            callerLogin = data["caller_login"]?.takeIf(String::isNotBlank),
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

    fun shouldShowMissed(pendingCallId: String?, snapshot: CallSnapshot): Boolean {
        if (pendingCallId != callId || (eventType != "call.cancel" && eventType != "call.expire")) return false
        return snapshot.callId != callId || snapshot.phase == CallPhase.Idle || snapshot.phase == CallPhase.Ringing
    }
}

enum class PushAction {
    Show,
    Cancel,
}
