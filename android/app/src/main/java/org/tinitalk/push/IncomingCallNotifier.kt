package org.tinitalk.push

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Person
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import org.tinitalk.CallActivity
import org.tinitalk.MainActivity
import org.tinitalk.R
import org.tinitalk.call.CallPhase
import org.tinitalk.call.CallSnapshot
import org.tinitalk.data.CallUnreadState
import org.tinitalk.data.Session
import org.tinitalk.telecom.IncomingCallController
import java.time.Instant
import java.time.Duration
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

data class IncomingInvite(
    val callId: String,
    val caller: String,
    val expiresAt: Instant,
    val callerLogin: String? = null,
    val lastSeq: Long = 0,
)

internal data class MissedBadgeUpdate(val applied: Boolean, val count: Int)

internal fun acknowledgeLatestMissedCall(
    login: String,
    loadLatestId: (String) -> Long?,
    markRead: (String, Long) -> CallUnreadState?,
): CallUnreadState? {
    val latestId = loadLatestId(login)?.takeIf { it > 0L } ?: return null
    return markRead(login, latestId)
}

internal class MissedBadgeCounter {
    private var nextRefreshId = 0L
    private var count = 0

    @Synchronized
    fun beginRefresh(): Long = ++nextRefreshId

    @Synchronized
    fun update(refreshId: Long, count: Int): MissedBadgeUpdate {
        if (refreshId < nextRefreshId) return MissedBadgeUpdate(false, this.count)
        this.count = count.coerceAtLeast(0)
        return MissedBadgeUpdate(true, this.count)
    }

    @Synchronized
    fun reset() {
        nextRefreshId++
        count = 0
    }

    @Synchronized
    fun snapshot(): Int = count
}

internal class MissedBadgeUpdater(
    private val counter: MissedBadgeCounter,
    private val execute: ((() -> Unit) -> Unit),
) {
    private val observers = CopyOnWriteArraySet<(Int) -> Unit>()

    fun beginRefresh(): Long = counter.beginRefresh()

    @Synchronized
    fun observe(observer: (Int) -> Unit) {
        observers += observer
        observer(counter.snapshot())
    }

    @Synchronized
    fun removeObserver(observer: (Int) -> Unit) {
        observers -= observer
    }

    @Synchronized
    fun update(refreshId: Long, count: Int, publish: (Int) -> Unit): MissedBadgeUpdate {
        val update = counter.update(refreshId, count)
        if (update.applied) {
            notifyObservers(update.count)
            execute { publish(update.count) }
        }
        return update
    }

    fun updateImmediately(refreshId: Long, count: Int, publish: (Int) -> Unit): MissedBadgeUpdate {
        val update = synchronized(this) {
            counter.update(refreshId, count).also {
                if (it.applied) notifyObservers(it.count)
            }
        }
        if (update.applied) publish(update.count)
        return update
    }

    @Synchronized
    fun clear(publish: (Int) -> Unit) {
        counter.reset()
        notifyObservers(0)
        execute { publish(0) }
    }

    private fun notifyObservers(count: Int) {
        observers.forEach { observer -> runCatching { observer(count) } }
    }
}

private val MissedBadgeExecutor = Executors.newSingleThreadExecutor { task ->
    Thread(task, "tinitalk-missed-badge").apply { isDaemon = true }
}
private val MissedBadges = MissedBadgeUpdater(MissedBadgeCounter()) { task ->
    MissedBadgeExecutor.execute { runCatching { task() } }
}

internal class IncomingCallForegroundPresentation(
    private val enterForeground: (IncomingInvite) -> Unit,
    private val acknowledgeRinging: (IncomingInvite) -> Unit,
    private val openFullScreen: (IncomingInvite) -> Unit,
) {
    fun present(invite: IncomingInvite, mode: IncomingCallPresentationMode) {
        enterForeground(invite)
        acknowledgeRinging(invite)
        if (mode == IncomingCallPresentationMode.InApp) openFullScreen(invite)
    }
}

internal class IncomingCallAlertHandoff(
    private val startVibration: (IncomingInvite) -> Unit,
    private val startRingtone: (IncomingInvite) -> Unit,
    private val dismissNotification: () -> Unit,
) {
    fun fullScreenShown(invite: IncomingInvite) {
        startVibration(invite)
        startRingtone(invite)
        dismissNotification()
    }
}

private object IncomingVibration {
    private val pattern = longArrayOf(0, 700, 500, 700, 1_500)
    private val handler = Handler(Looper.getMainLooper())
    private var callId: String? = null
    private var vibrator: Vibrator? = null
    private var stopTask: Runnable? = null

    @Synchronized
    fun start(context: Context, invite: IncomingInvite) {
        if (callId == invite.callId) return
        stop()

        val next = getVibrator(context.applicationContext) ?: return
        if (!next.hasVibrator()) return

        callId = invite.callId
        vibrator = next
        val task = Runnable { stop(invite.callId) }
        stopTask = task
        handler.postDelayed(
            task,
            Duration.between(Instant.now(), invite.expiresAt).toMillis().coerceAtLeast(0),
        )
        runCatching {
            next.vibrate(VibrationEffect.createWaveform(pattern, 0))
        }.onFailure {
            stop(invite.callId)
        }
    }

    @Synchronized
    fun stop(expectedCallId: String? = null) {
        if (expectedCallId != null && callId != expectedCallId) return
        stopTask?.let(handler::removeCallbacks)
        stopTask = null
        runCatching { vibrator?.cancel() }
        vibrator = null
        callId = null
    }

    @Suppress("DEPRECATION")
    private fun getVibrator(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
}

private object IncomingRingtone {
    private val handler = Handler(Looper.getMainLooper())
    private var callId: String? = null
    private var ringtone: Ringtone? = null
    private var stopTask: Runnable? = null

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
        val task = Runnable { stop(invite.callId) }
        stopTask = task
        handler.postDelayed(
            task,
            Duration.between(Instant.now(), invite.expiresAt).toMillis().coerceAtLeast(0),
        )
        runCatching { next.play() }.onFailure { stop(invite.callId) }
    }

    @Synchronized
    fun stop(expectedCallId: String? = null) {
        if (expectedCallId != null && callId != expectedCallId) return
        stopTask?.let(handler::removeCallbacks)
        stopTask = null
        runCatching { ringtone?.stop() }
        ringtone = null
        callId = null
    }
}

class IncomingCallNotifier(private val context: Context) {
    fun show(invite: IncomingInvite) {
        val mode = currentIncomingCallPresentation(context, appVisible = false)
        buildIncomingNotification(invite, mode) { notification ->
            context.getSystemService(NotificationManager::class.java).notify(NotificationId, notification)
        }
    }

    internal fun buildIncomingNotification(invite: IncomingInvite): Notification? =
        buildIncomingNotification(invite, currentIncomingCallPresentation(context)) {}

    internal fun buildIncomingNotification(
        invite: IncomingInvite,
        mode: IncomingCallPresentationMode,
    ): Notification? = buildIncomingNotification(invite, mode) {}

    internal fun presentIncoming(
        invite: IncomingInvite,
        mode: IncomingCallPresentationMode,
        publish: (Notification) -> Unit,
    ): Boolean = buildIncomingNotification(invite, mode, publish) != null

    private fun buildIncomingNotification(
        invite: IncomingInvite,
        mode: IncomingCallPresentationMode,
        publish: (Notification) -> Unit,
    ): Notification? {
        ensureChannel(mode)
        val controller = IncomingCallController()
        var notification: Notification? = null
        val presented = controller.presentIncoming(context, invite) {
            IncomingVibration.start(context, invite)
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(
                    context,
                    if (mode == IncomingCallPresentationMode.InApp) InAppChannelId else ChannelId,
                )
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(context)
            }
            val answer = controller.activityIntent(context, IncomingCallController.ActionAnswer, invite)
            val reject = controller.actionIntent(context, IncomingCallController.ActionReject, invite)
            val fullScreen = controller.activityIntent(context, IncomingCallController.ActionIncoming, invite)
            @Suppress("DEPRECATION")
            builder
                .setSmallIcon(R.drawable.ic_call_ringing)
                .setContentTitle("Входящий звонок")
                .setContentText(invite.caller.ifEmpty { "TiniTalk" })
                .setCategory(Notification.CATEGORY_CALL)
                .setPriority(
                    if (mode == IncomingCallPresentationMode.InApp) {
                        Notification.PRIORITY_LOW
                    } else {
                        Notification.PRIORITY_HIGH
                    },
                )
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentIntent(fullScreen)
                .setOngoing(true)
                .setTimeoutAfter(Duration.between(Instant.now(), invite.expiresAt).toMillis().coerceAtLeast(0))
            if (mode == IncomingCallPresentationMode.FullScreen) {
                builder.setFullScreenIntent(fullScreen, true)
            }
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
                    .addAction(
                        Notification.Action.Builder(
                            Icon.createWithResource(context, R.drawable.ic_call),
                            "Отклонить",
                            reject,
                        ).build(),
                    )
                    .addAction(
                        Notification.Action.Builder(
                            Icon.createWithResource(context, R.drawable.ic_call),
                            "Ответить",
                            answer,
                        ).build(),
                    )
            }
            notification = builder.build()
            publish(requireNotNull(notification))
        }
        return notification.takeIf { presented }
    }

    fun showMissed(invite: IncomingInvite? = null) {
        updateMissedCountImmediately(1, beginMissedCountRefresh(), invite)
    }

    fun showMissedIfAbsent(invite: IncomingInvite) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.activeNotifications.none { it.id == MissedNotificationId }) showMissed(invite)
    }

    fun beginMissedCountRefresh(): Long = MissedBadges.beginRefresh()

    fun observeMissedCount(observer: (Int) -> Unit) = MissedBadges.observe(observer)

    fun removeMissedCountObserver(observer: (Int) -> Unit) = MissedBadges.removeObserver(observer)

    internal fun updateMissedCount(count: Int, refreshId: Long, latest: IncomingInvite? = null): MissedBadgeUpdate =
        MissedBadges.update(refreshId, count) { publishMissedCount(it, latest) }

    internal fun updateMissedState(
        unread: CallUnreadState,
        refreshId: Long,
        latest: IncomingInvite? = null,
    ): MissedBadgeUpdate =
        MissedBadges.update(refreshId, unread.unreadMissedCount) {
            publishMissedCount(it, latest, unread.unreadMissed.firstOrNull()?.peerLogin)
        }

    internal fun updateMissedCountImmediately(
        count: Int,
        refreshId: Long,
        latest: IncomingInvite? = null,
    ): MissedBadgeUpdate =
        MissedBadges.updateImmediately(refreshId, count) { publishMissedCount(it, latest) }

    internal fun updateMissedStateImmediately(
        unread: CallUnreadState,
        refreshId: Long,
        latest: IncomingInvite? = null,
    ): MissedBadgeUpdate =
        MissedBadges.updateImmediately(refreshId, unread.unreadMissedCount) {
            publishMissedCount(it, latest, unread.unreadMissed.firstOrNull()?.peerLogin)
        }

    fun clearMissedCount() {
        MissedBadges.clear { publishMissedCount(it, null) }
    }

    private fun publishMissedCount(
        count: Int,
        latest: IncomingInvite?,
        latestUnreadLogin: String? = null,
    ) {
        ensureMissedChannel()
        val manager = context.getSystemService(NotificationManager::class.java)
        if (count <= 0) {
            manager.cancel(MissedNotificationId)
            return
        }
        val redialLogin = latestUnreadLogin?.takeIf(String::isNotBlank)
            ?: latest?.callerLogin?.takeIf(String::isNotBlank)
        val matchingLatest = latest?.takeIf { it.callerLogin == redialLogin }
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
            .setSmallIcon(R.drawable.ic_call_missed)
            .setContentTitle(if (count == 1) "Пропущенный звонок" else "Пропущенные звонки")
            .setContentText(
                matchingLatest?.caller?.takeIf { count == 1 && it.isNotBlank() }
                    ?: "$count ${missedCallsWord(count)}",
            )
            .setCategory(Notification.CATEGORY_MISSED_CALL)
            .setContentIntent(openApp)
            .setAutoCancel(false)
            .setOngoing(true)
            .setNumber(count)
            .setBadgeIconType(Notification.BADGE_ICON_SMALL)
            .setOnlyAlertOnce(true)
        redialLogin?.let { login ->
            val redial = PendingIntent.getActivity(
                context,
                (matchingLatest?.callId ?: "missed:$login").hashCode(),
                CallActivity.redialIntent(context, login, matchingLatest?.caller?.ifBlank { login } ?: login),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, R.drawable.ic_call),
                    if (count == 1) "Перезвонить" else "Перезвонить последнему",
                    redial,
                ).build(),
            )
        }
        manager.notify(MissedNotificationId, builder.build())
    }

    fun cancel() {
        dismissNotification()
        IncomingVibration.stop()
        IncomingRingtone.stop()
    }

    fun fullScreenShown(invite: IncomingInvite) {
        IncomingCallAlertHandoff(
            startVibration = { IncomingVibration.start(context, it) },
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

    private fun ensureChannel(mode: IncomingCallPresentationMode) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (mode == IncomingCallPresentationMode.InApp) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    InAppChannelId,
                    "Входящий звонок в приложении",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Служебное уведомление во время показа входящего звонка"
                    enableVibration(false)
                    setSound(null, null)
                    setShowBadge(false)
                },
            )
            return
        }
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
            setShowBadge(false)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun ensureMissedChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(MissedChannelId, "Пропущенные звонки", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(true)
            },
        )
    }

    private fun missedCallsWord(count: Int): String {
        val lastTwo = count % 100
        if (lastTwo in 11..14) return "пропущенных вызовов"
        return when (count % 10) {
            1 -> "пропущенный вызов"
            2, 3, 4 -> "пропущенных вызова"
            else -> "пропущенных вызовов"
        }
    }

    companion object {
        private const val ChannelId = "incoming_calls_v2"
        private const val InAppChannelId = "incoming_calls_in_app_v1"
        private const val MissedChannelId = "missed_calls_v2"
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

    fun matchesTarget(data: Map<String, String>, session: Session?, deviceId: String): Boolean {
        val keys = listOf("target_login", "target_device_id", "target_session_id")
        if (keys.none(data::containsKey)) return true
        if (!keys.all(data::containsKey)) return false
        session ?: return false
        return data["target_login"] == session.login &&
            data["target_device_id"] == deviceId &&
            data["target_session_id"].normalizedSessionId() == session.sessionId
    }

    fun sessionReplacement(data: Map<String, String>): SessionReplacementPayload? {
        if (data["type"] != "session_replaced" ||
            !data.containsKey("login") ||
            !data.containsKey("revoked_session_id") ||
            !data.containsKey("revoked_device_id")
        ) {
            return null
        }
        val login = data["login"].orEmpty()
        val deviceId = data["revoked_device_id"].orEmpty()
        if (login.isEmpty() || deviceId.isEmpty()) return null
        return SessionReplacementPayload(
            login,
            data["revoked_session_id"].normalizedSessionId(),
            deviceId,
        )
    }
}

data class SessionReplacementPayload(
    val login: String,
    val revokedSessionId: String?,
    val revokedDeviceId: String,
) {
    fun matches(session: Session, deviceId: String): Boolean =
        login == session.login &&
            revokedDeviceId == deviceId &&
            revokedSessionId == session.sessionId
}

private fun String?.normalizedSessionId(): String? = this?.takeIf(String::isNotEmpty)

data class CallCancellation(val callId: String, val eventType: String) {
    fun shouldDismiss(pendingCallId: String?, snapshot: CallSnapshot): Boolean {
        if (shouldEndActive(snapshot)) return true
        if (pendingCallId != callId) return false
        return eventType != "call.accept" ||
            snapshot.callId != callId || snapshot.phase != CallPhase.Active
    }

    fun shouldEndActive(snapshot: CallSnapshot): Boolean =
        eventType == "call.end" && snapshot.callId == callId &&
            snapshot.phase != CallPhase.Idle && snapshot.phase != CallPhase.Ended

    fun shouldRouteRemoteEnd(pendingCallId: String?, snapshot: CallSnapshot): Boolean =
        shouldEndActive(snapshot) ||
            eventType == "call.end" && (pendingCallId == null || pendingCallId == callId)

    fun shouldShowMissed(pendingCallId: String?, snapshot: CallSnapshot): Boolean {
        if (pendingCallId != callId || (eventType != "call.cancel" && eventType != "call.expire")) return false
        return snapshot.callId != callId || snapshot.phase == CallPhase.Idle || snapshot.phase == CallPhase.Ringing
    }

    fun missedFallback(
        pending: IncomingInvite?,
        snapshot: CallSnapshot,
        now: Instant = Instant.now(),
    ): IncomingInvite? = pending?.takeIf {
        it.expiresAt.isAfter(now) && shouldShowMissed(it.callId, snapshot)
    }

    fun shouldRefreshMissedCount(): Boolean =
        eventType == "call.cancel" || eventType == "call.expire" || eventType == "call.busy"
}

enum class PushAction {
    Show,
    Cancel,
}
