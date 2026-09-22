package org.tinitalk.push

import org.tinitalk.i18n.appString

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Person
import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.VibrationAttributes
import android.os.Vibrator
import android.os.VibratorManager
import org.tinitalk.R
import org.tinitalk.call.CallPhase
import org.tinitalk.call.CallSnapshot
import org.tinitalk.call.AccountCallKey
import org.tinitalk.call.AccountCallOwner
import org.tinitalk.call.CallSessionBinding
import org.tinitalk.data.ContactAddress
import org.tinitalk.data.AccountId
import org.tinitalk.data.AccountRecord
import org.tinitalk.data.Session
import org.tinitalk.contactPhotoNotificationLoader
import org.tinitalk.telecom.IncomingCallController
import java.time.Instant
import java.time.Duration

data class IncomingInvite(
    val accountId: AccountId,
    val sessionBinding: CallSessionBinding,
    val callId: String,
    val caller: String,
    val expiresAt: Instant,
    val callerLogin: String? = null,
    val lastSeq: Long = 0,
    val startedAt: Instant? = null,
) {
    val key: AccountCallKey get() = AccountCallKey(accountId, callId)
    val owner: AccountCallOwner get() = AccountCallOwner(key, sessionBinding)
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
    private val dismissNotification: (IncomingInvite) -> Unit,
    private val isSilenced: (IncomingInvite) -> Boolean,
    private val stopVibration: (AccountCallOwner) -> Unit,
    private val stopRingtone: (AccountCallOwner) -> Unit,
) {
    fun fullScreenShown(invite: IncomingInvite) {
        if (!isSilenced(invite)) {
            startVibration(invite)
            startRingtone(invite)
        }
        dismissNotification(invite)
    }

    fun silence(invite: IncomingInvite) {
        stopVibration(invite.owner)
        stopRingtone(invite.owner)
    }
}

private object IncomingVibration {
    private val pattern = longArrayOf(0, 700, 500, 700, 1_500)
    private val handler = Handler(Looper.getMainLooper())
    private var callOwner: AccountCallOwner? = null
    private var vibrator: Vibrator? = null
    private var stopTask: Runnable? = null

    @Synchronized
    fun start(context: Context, invite: IncomingInvite) {
        if (incomingCallSilenceStore(context).isSilenced(invite)) return
        if (callOwner == invite.owner) return
        stop()

        val next = getVibrator(context.applicationContext) ?: return
        if (!next.hasVibrator()) return

        callOwner = invite.owner
        vibrator = next
        val task = Runnable { stop(invite.owner) }
        stopTask = task
        handler.postDelayed(
            task,
            Duration.between(Instant.now(), invite.expiresAt).toMillis().coerceAtLeast(0),
        )
        runCatching {
            val effect = VibrationEffect.createWaveform(pattern, 0)
            // Let Android apply ringtone settings and Do Not Disturb to the manual loop too.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                next.vibrate(effect, VibrationAttributes.Builder()
                    .setUsage(VibrationAttributes.USAGE_RINGTONE)
                    .build())
            } else {
                @Suppress("DEPRECATION")
                next.vibrate(effect, AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .build())
            }
        }.onFailure {
            stop(invite.owner)
        }
    }

    @Synchronized
    fun stop(expectedOwner: AccountCallOwner? = null) {
        if (expectedOwner != null && callOwner != expectedOwner) return
        stopTask?.let(handler::removeCallbacks)
        stopTask = null
        runCatching { vibrator?.cancel() }
        vibrator = null
        callOwner = null
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
    private var callOwner: AccountCallOwner? = null
    private var ringtone: Ringtone? = null
    private var stopTask: Runnable? = null

    @Synchronized
    fun start(context: Context, invite: IncomingInvite) {
        if (incomingCallSilenceStore(context).isSilenced(invite)) return
        if (callOwner == invite.owner && ringtone?.isPlaying == true) return
        stop()

        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE) ?: return
        val next = RingtoneManager.getRingtone(context.applicationContext, uri) ?: return
        next.audioAttributes = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) next.isLooping = true

        callOwner = invite.owner
        ringtone = next
        val task = Runnable { stop(invite.owner) }
        stopTask = task
        handler.postDelayed(
            task,
            Duration.between(Instant.now(), invite.expiresAt).toMillis().coerceAtLeast(0),
        )
        runCatching { next.play() }.onFailure { stop(invite.owner) }
    }

    @Synchronized
    fun stop(expectedOwner: AccountCallOwner? = null) {
        if (expectedOwner != null && callOwner != expectedOwner) return
        stopTask?.let(handler::removeCallbacks)
        stopTask = null
        runCatching { ringtone?.stop() }
        ringtone = null
        callOwner = null
    }
}

class IncomingCallNotifier internal constructor(
    private val context: Context,
    photoLoader: ContactPhotoNotificationLoader?,
    private val alertHandoff: IncomingCallAlertHandoff?,
) {
    constructor(context: Context, photoLoader: ContactPhotoNotificationLoader? = null) : this(context, photoLoader, null)

    private val alerts by lazy {
        alertHandoff ?: IncomingCallAlertHandoff(
            startVibration = { IncomingVibration.start(context, it) },
            startRingtone = { IncomingRingtone.start(context, it) },
            dismissNotification = { IncomingCallForegroundService.hideNotification(context, it) },
            isSilenced = { incomingCallSilenceStore(context).isSilenced(it) },
            stopVibration = { IncomingVibration.stop(it) },
            stopRingtone = { IncomingRingtone.stop(it) },
        )
    }
    private val photoLoader: ContactPhotoNotificationLoader by lazy {
        photoLoader ?: contactPhotoNotificationLoader(context)
    }

    fun show(invite: IncomingInvite) {
        val mode = currentIncomingCallPresentation(context, appVisible = false)
        presentIncoming(invite, mode) { notification ->
            context.getSystemService(NotificationManager::class.java).notify(NotificationId, notification)
        }
    }

    internal fun buildIncomingNotification(invite: IncomingInvite): Notification? =
        buildIncomingNotification(invite, currentIncomingCallPresentation(context))

    internal fun buildIncomingNotification(
        invite: IncomingInvite,
        mode: IncomingCallPresentationMode,
        bitmap: Bitmap? = null,
        foregroundService: Boolean = false,
    ): Notification? = buildIncomingNotification(invite, mode, bitmap, foregroundService) {}

    internal fun presentIncoming(
        invite: IncomingInvite,
        mode: IncomingCallPresentationMode,
        foregroundService: Boolean = false,
        publish: (Notification) -> Unit,
    ): Boolean = buildIncomingNotification(
        invite, mode, incomingPhotoAddress(invite)?.let(photoLoader::peek), foregroundService, publish,
    ) != null

    private fun buildIncomingNotification(
        invite: IncomingInvite,
        mode: IncomingCallPresentationMode,
        bitmap: Bitmap?,
        foregroundService: Boolean,
        publish: (Notification) -> Unit,
    ): Notification? {
        ensureChannel(mode)
        val controller = IncomingCallController()
        var notification: Notification? = null
        val presented = controller.presentSavedIncoming(context, invite) {
            IncomingVibration.start(context, invite)
            val answer = controller.activityIntent(context, IncomingCallController.ActionAnswer, invite)
            val reject = controller.actionIntent(context, IncomingCallController.ActionReject, invite)
            val fullScreen = controller.activityIntent(context, IncomingCallController.ActionIncoming, invite)
            val builder = incomingNotificationBuilder(invite, mode, answer, reject, fullScreen, bitmap, foregroundService)
            notification = builder.build()
            publish(requireNotNull(notification))
            enqueueIncomingPhotoRefresh(invite, answer, reject, bitmap)
        }
        return notification.takeIf { presented }
    }

    private fun incomingNotificationBuilder(
        invite: IncomingInvite,
        mode: IncomingCallPresentationMode,
        answer: PendingIntent,
        reject: PendingIntent,
        fullScreen: PendingIntent,
        bitmap: Bitmap?,
        foregroundService: Boolean,
    ): Notification.Builder {
        // A standalone CallStyle is rejected on newer Android unless it has a usable
        // fullScreenIntent. Only use it when publishing through startForeground.
        val useCallStyle = Build.VERSION.SDK_INT >= 31 && foregroundService
        val builder = Notification.Builder(
            org.tinitalk.i18n.AppLanguage.context(context),
            if (mode == IncomingCallPresentationMode.InApp) InAppChannelId else ChannelId,
        )
        val notificationPhoto = bitmap?.let(::roundedNotificationPhoto)
        notificationPhoto?.let(builder::setLargeIcon)
        val personBuilder = if (Build.VERSION.SDK_INT >= 28) {
            Person.Builder()
                .setName(invite.caller.ifEmpty { "TiniTalk" })
                .setImportant(true)
                .also { person ->
                    if (Build.VERSION.SDK_INT >= 31) {
                        notificationPhoto?.let { person.setIcon(Icon.createWithBitmap(it)) }
                    }
                }
        } else {
            null
        }
            @Suppress("DEPRECATION")
            builder
                .setSmallIcon(R.drawable.ic_call_ringing)
                .setContentTitle(appString(R.string.text_incoming_call_62))
                .setContentText(
                    if (useCallStyle) appString(R.string.text_incoming_call_62) else invite.caller.ifEmpty { "TiniTalk" },
                )
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
                .setBadgeIconType(Notification.BADGE_ICON_NONE)
                .addExtras(Bundle().apply { putString(NotificationOwnerExtra, invite.owner.localId()) })
            if (mode == IncomingCallPresentationMode.FullScreen) {
                builder.setFullScreenIntent(fullScreen, true)
            }
            if (Build.VERSION.SDK_INT >= 31 && useCallStyle) {
                builder.setStyle(
                    Notification.CallStyle.forIncomingCall(
                        requireNotNull(personBuilder).build(),
                        reject,
                        answer,
                    ),
                )
            } else {
                builder
                    .addAction(
                        Notification.Action.Builder(
                            Icon.createWithResource(context, R.drawable.ic_call),
                            appString(R.string.text_decline_63),
                            reject,
                        ).build(),
                    )
                    .addAction(
                        Notification.Action.Builder(
                            Icon.createWithResource(context, R.drawable.ic_call),
                            appString(R.string.text_answer_64),
                            answer,
                        ).build(),
                    )
            }
        applySilence(invite, builder)
        return builder
    }

    private fun applySilence(invite: IncomingInvite, builder: Notification.Builder) {
        if (incomingCallSilenceStore(context).isSilenced(invite)) {
            // The child of this silent group never alerts, including when the channel has sound.
            builder.setGroup("incoming_call_silenced")
                .setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)
                .setOnlyAlertOnce(true)
        }
    }

    private fun enqueueIncomingPhotoRefresh(
        invite: IncomingInvite,
        answer: PendingIntent,
        reject: PendingIntent,
        initialBitmap: Bitmap?,
    ) {
        val address = incomingPhotoAddress(invite) ?: return
        if (initialBitmap != null) return
        val revision = photoLoader.revision
        val requestKey = invite.owner.localId()
        photoLoader.load(address, requestKey, revision) { loadedKey, capturedRevision, bitmap ->
            if (loadedKey != requestKey || bitmap == null) return@load
            // Serialize with the Activity/Service handoff. A late photo must not resurrect
            // a hidden notification or turn a standalone fallback back into CallStyle.
            Handler(Looper.getMainLooper()).post {
                if (capturedRevision != photoLoader.revision) return@post
                IncomingCallController().presentSavedIncoming(context, invite) refresh@{
                    if (IncomingCallScreenState.isShowing(invite.owner)) return@refresh
                    val manager = context.getSystemService(NotificationManager::class.java)
                    val current = manager.activeNotifications.firstOrNull { it.id == NotificationId }?.notification
                        ?: return@refresh
                    if (current.extras.getString(NotificationOwnerExtra) != requestKey) return@refresh
                    val photo = roundedNotificationPhoto(bitmap)
                    val builder = Notification.Builder.recoverBuilder(context, current)
                        .setLargeIcon(photo)
                        .setOnlyAlertOnce(true)
                        .setTimeoutAfter(Duration.between(Instant.now(), invite.expiresAt).toMillis().coerceAtLeast(1))
                    if (Build.VERSION.SDK_INT >= 31 &&
                        current.extras.getString(Notification.EXTRA_TEMPLATE) == Notification.CallStyle::class.java.name
                    ) {
                        builder.setStyle(Notification.CallStyle.forIncomingCall(
                            Person.Builder().setName(invite.caller.ifEmpty { "TiniTalk" }).setImportant(true)
                                .setIcon(Icon.createWithBitmap(photo)).build(),
                            reject, answer,
                        ))
                    }
                    applySilence(invite, builder)
                    manager.notify(NotificationId, builder.build())
                }
            }
        }
    }

    private fun incomingPhotoAddress(invite: IncomingInvite): ContactAddress? =
        invite.callerLogin?.takeIf(String::isNotBlank)
            ?.let { login -> ContactAddress.of(invite.sessionBinding.serverUrl, login) }

    fun cancel() {
        IncomingCallScreenState.hidden()
        dismissNotification()
        IncomingVibration.stop()
        IncomingRingtone.stop()
    }

    fun silence(invite: IncomingInvite): Boolean =
        IncomingCallController().withCurrentIncoming(context, invite) {
            incomingCallSilenceStore(context).silence(invite)
            alerts.silence(invite)
        }

    fun fullScreenShown(invite: IncomingInvite) {
        IncomingCallController().withCurrentIncoming(context, invite) {
            IncomingCallScreenState.shown(invite.owner)
            alerts.fullScreenShown(invite)
        }
    }

    fun fullScreenHidden(invite: IncomingInvite) {
        IncomingCallScreenState.hidden(invite.owner)
        IncomingRingtone.stop(invite.owner)
        IncomingCallController().presentSavedIncoming(context, invite) {
            if (!IncomingCallForegroundService.restoreNotification(context, invite)) {
                show(invite)
                scheduleIncomingExpiry(context, invite)
            }
        }
    }

    private fun dismissNotification() {
        IncomingCallForegroundService.stop(context)
        context.getSystemService(NotificationManager::class.java).cancel(NotificationId)
    }

    private fun ensureChannel(mode: IncomingCallPresentationMode) {
        if (mode == IncomingCallPresentationMode.InApp) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    InAppChannelId,
                    appString(R.string.text_incoming_call_in_the_app_65),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = appString(R.string.text_service_notification_while_the_incoming_call_screen_is_shown_66)
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
        val channel = NotificationChannel(ChannelId, appString(R.string.text_incoming_calls_67), NotificationManager.IMPORTANCE_HIGH).apply {
            description = appString(R.string.text_ringtone_and_vibration_for_incoming_calls_68)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            enableVibration(true)
            setSound(ringtone, audio)
            setShowBadge(false)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val ChannelId = "incoming_calls_v2"
        private const val InAppChannelId = "incoming_calls_in_app_v1"
        private const val NotificationOwnerExtra = "org.tinitalk.incoming.owner"
        internal const val NotificationId = 11
    }
}

object IncomingPushPayload {
    fun action(data: Map<String, String>): PushAction =
        if (data["type"] == "call_cancel") PushAction.Cancel else PushAction.Show

    fun parse(
        data: Map<String, String>,
        account: AccountRecord,
        now: Instant = Instant.now(),
    ): IncomingInvite? {
        if (data["type"] != "incoming_call") return null
        val callId = data["call_id"].orEmpty()
        val expiresAt = runCatching { Instant.parse(data["expires_at"]) }.getOrNull() ?: return null
        if (callId.isEmpty() || !expiresAt.isAfter(now)) return null
        return IncomingInvite(
            accountId = account.id,
            sessionBinding = CallSessionBinding.from(account.session),
            callId = callId,
            caller = data["caller"].orEmpty(),
            callerLogin = data["caller_login"]?.takeIf(String::isNotBlank),
            expiresAt = expiresAt,
            lastSeq = data["last_seq"]?.toLongOrNull() ?: 0,
            startedAt = data["started_at"]?.let { runCatching { Instant.parse(it) }.getOrNull() },
        )
    }

    fun cancellation(data: Map<String, String>, accountId: AccountId): CallCancellation? {
        if (data["type"] != "call_cancel") return null
        val callId = data["call_id"].orEmpty()
        if (callId.isEmpty()) return null
        return CallCancellation(AccountCallKey(accountId, callId), data["call_event"].orEmpty())
    }

    fun matchesTarget(data: Map<String, String>, session: Session?, deviceId: String): Boolean {
        val keys = listOf("target_login", "target_device_id", "target_session_id")
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

data class CallCancellation(val key: AccountCallKey, val eventType: String) {
    fun shouldDismiss(pendingKey: AccountCallKey?, snapshot: CallSnapshot): Boolean {
        if (shouldEndActive(snapshot)) return true
        if (pendingKey != key) return false
        return eventType != "call.accept" ||
            snapshot.callKey != key || snapshot.phase != CallPhase.Active
    }

    fun shouldEndActive(snapshot: CallSnapshot): Boolean =
        eventType == "call.end" && snapshot.callKey == key &&
            snapshot.phase != CallPhase.Idle && snapshot.phase != CallPhase.Ended

    fun shouldRouteRemoteEnd(pendingKey: AccountCallKey?, snapshot: CallSnapshot): Boolean =
        shouldEndActive(snapshot) ||
            eventType == "call.end" && (pendingKey == null || pendingKey == key)

    fun shouldShowMissed(pendingKey: AccountCallKey?, snapshot: CallSnapshot): Boolean {
        if (pendingKey != key || (eventType != "call.cancel" && eventType != "call.expire")) return false
        return snapshot.callKey != key || snapshot.phase == CallPhase.Idle || snapshot.phase == CallPhase.Ringing
    }

    fun missedFallback(
        pending: IncomingInvite?,
        snapshot: CallSnapshot,
        now: Instant = Instant.now(),
    ): IncomingInvite? = pending?.takeIf {
        it.expiresAt.isAfter(now) && shouldShowMissed(it.key, snapshot)
    }

    fun shouldRefreshMissedCount(): Boolean =
        eventType == "call.cancel" || eventType == "call.expire" || eventType == "call.busy"
}

enum class PushAction {
    Show,
    Cancel,
}
