package org.tinitalk.push

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Person
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
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
import org.tinitalk.call.AccountCallKey
import org.tinitalk.call.AccountCallOwner
import org.tinitalk.call.CallSessionBinding
import org.tinitalk.data.ContactAddress
import org.tinitalk.data.CallUnreadState
import org.tinitalk.data.UnreadMissedContact
import org.tinitalk.data.AccountId
import org.tinitalk.data.AccountPeerKey
import org.tinitalk.data.AccountRecord
import org.tinitalk.data.Session
import org.tinitalk.contactPhotoNotificationLoader
import org.tinitalk.telecom.IncomingCallController
import java.time.Instant
import java.time.Duration
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import org.json.JSONObject

data class IncomingInvite(
    val accountId: AccountId,
    val sessionBinding: CallSessionBinding,
    val callId: String,
    val caller: String,
    val expiresAt: Instant,
    val callerLogin: String? = null,
    val lastSeq: Long = 0,
) {
    val key: AccountCallKey get() = AccountCallKey(accountId, callId)
    val owner: AccountCallOwner get() = AccountCallOwner(key, sessionBinding)
}

internal fun shouldOfferMissedRedial(login: String?, hasAccountIdentity: Boolean): Boolean =
    !login.isNullOrBlank() && hasAccountIdentity

internal fun acknowledgeLatestMissedCall(
    login: String,
    loadLatestId: (String) -> Long?,
    markRead: (String, Long) -> CallUnreadState?,
): CallUnreadState? {
    val latestId = loadLatestId(login)?.takeIf { it > 0L } ?: return null
    return markRead(login, latestId)
}

/** Per-account generation gates prevent an old server response from replacing another server's badge. */
internal class AccountMissedBadgeCounter(initial: Map<AccountId, Int> = emptyMap()) {
    private val counts = initial.mapValues { it.value.coerceAtLeast(0) }.toMutableMap()
    private val generations = initial.keys.associateWith { 0L }.toMutableMap()
    private var revision = 0L

    @Synchronized fun sync(active: Collection<AccountId>, persisted: Map<AccountId, Int> = emptyMap()): Int {
        val allowed = active.toSet()
        counts.keys.retainAll(allowed)
        generations.keys.retainAll(allowed)
        active.forEach { id ->
            counts.putIfAbsent(id, persisted[id]?.coerceAtLeast(0) ?: 0)
            generations.putIfAbsent(id, 0L)
        }
        revision++
        return counts.values.sum()
    }

    @Synchronized fun beginRefresh(accountId: AccountId): AccountBadgeRefreshId? {
        val previous = generations[accountId] ?: return null
        return AccountBadgeRefreshId(accountId, previous + 1L).also { generations[accountId] = it.generation }
    }

    @Synchronized fun update(accountId: AccountId, refreshId: AccountBadgeRefreshId?, count: Int): AccountMissedBadgeUpdate {
        if (refreshId?.accountId != accountId || generations[accountId] != refreshId.generation || accountId !in counts) {
            return AccountMissedBadgeUpdate(false, counts.values.sum(), revision)
        }
        counts[accountId] = count.coerceAtLeast(0)
        revision++
        return AccountMissedBadgeUpdate(true, counts.values.sum(), revision)
    }

    @Synchronized fun remove(accountId: AccountId): AccountMissedBadgeUpdate {
        counts.remove(accountId)
        generations.remove(accountId)
        revision++
        return AccountMissedBadgeUpdate(true, counts.values.sum(), revision)
    }

    @Synchronized fun snapshot(): Map<AccountId, Int> = counts.toMap()
    @Synchronized fun isCurrentRevision(value: Long): Boolean = revision == value
}

internal data class AccountMissedBadgeUpdate(val applied: Boolean, val count: Int, val revision: Long)
internal data class AccountBadgeRefreshId(val accountId: AccountId, val generation: Long)
internal data class MissedCallTarget(
    val occurredAt: Long,
    val accountId: AccountId,
    val latest: IncomingInvite?,
    val latestUnread: UnreadMissedContact?,
    val redialBinding: CallSessionBinding?,
)

internal class AccountMissedBadgeStore(private val preferences: SharedPreferences) {
    fun load(): Map<AccountId, Int> = runCatching {
        val objectValue = JSONObject(preferences.getString(AccountMissedBadgeKey, "{}") ?: "{}")
        objectValue.keys().asSequence().associate { raw -> AccountId(raw) to objectValue.optInt(raw).coerceAtLeast(0) }
    }.getOrDefault(emptyMap())

    fun save(counts: Map<AccountId, Int>) {
        val value = JSONObject().apply { counts.forEach { (id, count) -> put(id.value, count.coerceAtLeast(0)) } }
        preferences.edit().putString(AccountMissedBadgeKey, value.toString()).apply()
    }
}

internal class AccountMissedBadgeUpdater(
    private val counter: AccountMissedBadgeCounter,
    private val execute: ((() -> Unit) -> Unit),
) {
    private val observers = CopyOnWriteArraySet<(Int) -> Unit>()
    private val targets = mutableMapOf<AccountId, MissedCallTarget>()

    fun sync(active: Collection<AccountId>, persisted: Map<AccountId, Int>): Int = synchronized(this) {
        retainActiveTargets(active)
        counter.sync(active, persisted).also(::notifyObservers)
    }
    fun syncPersisted(active: Collection<AccountId>, load: () -> Map<AccountId, Int>, save: (Map<AccountId, Int>) -> Unit): Int = synchronized(this) {
        retainActiveTargets(active)
        counter.sync(active, load()).also { count -> save(counter.snapshot()); notifyObservers(count) }
    }
    fun beginRefresh(accountId: AccountId): AccountBadgeRefreshId? = counter.beginRefresh(accountId)
    fun observe(observer: (Int) -> Unit) { observers += observer; observer(counter.snapshot().values.sum()) }
    fun removeObserver(observer: (Int) -> Unit) { observers -= observer }
    fun remove(accountId: AccountId, persist: (Map<AccountId, Int>) -> Unit, publish: (Int) -> Unit) = synchronized(this) {
        targets.remove(accountId)
        counter.remove(accountId).also { update -> persist(counter.snapshot()); notifyObservers(update.count); execute { publishIfCurrent(update, publish) } }
    }
    fun update(accountId: AccountId, refreshId: AccountBadgeRefreshId?, count: Int, persist: (Map<AccountId, Int>) -> Unit, publish: (Int) -> Unit, target: MissedCallTarget? = null): AccountMissedBadgeUpdate = synchronized(this) {
        counter.update(accountId, refreshId, count).also { update ->
            if (update.applied) { updateTarget(accountId, count, target); persist(counter.snapshot()); notifyObservers(update.count); execute { publishIfCurrent(update, publish) } }
        }
    }
    fun updateImmediately(accountId: AccountId, refreshId: AccountBadgeRefreshId?, count: Int, persist: (Map<AccountId, Int>) -> Unit, publish: (Int) -> Unit, target: MissedCallTarget? = null): AccountMissedBadgeUpdate = synchronized(this) {
        counter.update(accountId, refreshId, count).also { update ->
            if (update.applied) { updateTarget(accountId, count, target); persist(counter.snapshot()); notifyObservers(update.count); publish(update.count) }
        }
    }
    private fun updateTarget(accountId: AccountId, count: Int, target: MissedCallTarget?) {
        if (count <= 0) targets.remove(accountId) else if (target != null) targets[accountId] = target
    }
    private fun retainActiveTargets(active: Collection<AccountId>) = targets.keys.retainAll(active.toSet())
    fun newestTarget(): MissedCallTarget? = synchronized(this) {
        targets.values.maxWithOrNull(compareBy<MissedCallTarget> { it.occurredAt }.thenBy { it.accountId.value })
    }
    private fun publishIfCurrent(update: AccountMissedBadgeUpdate, publish: (Int) -> Unit) = synchronized(this) {
        if (counter.isCurrentRevision(update.revision)) publish(update.count)
    }
    private fun notifyObservers(count: Int) = observers.forEach { observer -> runCatching { observer(count) } }
    fun snapshot(): Map<AccountId, Int> = counter.snapshot()
}

private val MissedBadgeExecutor = Executors.newSingleThreadExecutor { task ->
    Thread(task, "tinitalk-missed-badge").apply { isDaemon = true }
}
private val AccountMissedBadges = AccountMissedBadgeUpdater(AccountMissedBadgeCounter()) { task ->
    MissedBadgeExecutor.execute { runCatching { task() } }
}
private const val AccountMissedBadgeKey = "account_missed_badges"

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
    private var callKey: AccountCallKey? = null
    private var vibrator: Vibrator? = null
    private var stopTask: Runnable? = null

    @Synchronized
    fun start(context: Context, invite: IncomingInvite) {
        if (callKey == invite.key) return
        stop()

        val next = getVibrator(context.applicationContext) ?: return
        if (!next.hasVibrator()) return

        callKey = invite.key
        vibrator = next
        val task = Runnable { stop(invite.key) }
        stopTask = task
        handler.postDelayed(
            task,
            Duration.between(Instant.now(), invite.expiresAt).toMillis().coerceAtLeast(0),
        )
        runCatching {
            next.vibrate(VibrationEffect.createWaveform(pattern, 0))
        }.onFailure {
            stop(invite.key)
        }
    }

    @Synchronized
    fun stop(expectedCallKey: AccountCallKey? = null) {
        if (expectedCallKey != null && callKey != expectedCallKey) return
        stopTask?.let(handler::removeCallbacks)
        stopTask = null
        runCatching { vibrator?.cancel() }
        vibrator = null
        callKey = null
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
    private var callKey: AccountCallKey? = null
    private var ringtone: Ringtone? = null
    private var stopTask: Runnable? = null

    @Synchronized
    fun start(context: Context, invite: IncomingInvite) {
        if (callKey == invite.key && ringtone?.isPlaying == true) return
        stop()

        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE) ?: return
        val next = RingtoneManager.getRingtone(context.applicationContext, uri) ?: return
        next.audioAttributes = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) next.isLooping = true

        callKey = invite.key
        ringtone = next
        val task = Runnable { stop(invite.key) }
        stopTask = task
        handler.postDelayed(
            task,
            Duration.between(Instant.now(), invite.expiresAt).toMillis().coerceAtLeast(0),
        )
        runCatching { next.play() }.onFailure { stop(invite.key) }
    }

    @Synchronized
    fun stop(expectedCallKey: AccountCallKey? = null) {
        if (expectedCallKey != null && callKey != expectedCallKey) return
        stopTask?.let(handler::removeCallbacks)
        stopTask = null
        runCatching { ringtone?.stop() }
        ringtone = null
        callKey = null
    }
}

class IncomingCallNotifier(
    private val context: Context,
    photoLoader: ContactPhotoNotificationLoader? = null,
) {
    private val photoLoader: ContactPhotoNotificationLoader by lazy {
        photoLoader ?: contactPhotoNotificationLoader(context)
    }

    fun show(invite: IncomingInvite) {
        val mode = currentIncomingCallPresentation(context, appVisible = false)
        buildIncomingNotification(invite, mode, incomingPhotoAddress(invite)?.let(photoLoader::peek)) { notification ->
            context.getSystemService(NotificationManager::class.java).notify(NotificationId, notification)
        }
    }

    internal fun buildIncomingNotification(invite: IncomingInvite): Notification? =
        buildIncomingNotification(invite, currentIncomingCallPresentation(context), null) {}

    internal fun buildIncomingNotification(
        invite: IncomingInvite,
        mode: IncomingCallPresentationMode,
        bitmap: Bitmap? = null,
    ): Notification? = buildIncomingNotification(invite, mode, bitmap) {}

    internal fun presentIncoming(
        invite: IncomingInvite,
        mode: IncomingCallPresentationMode,
        publish: (Notification) -> Unit,
    ): Boolean = buildIncomingNotification(invite, mode, incomingPhotoAddress(invite)?.let(photoLoader::peek), publish) != null

    private fun buildIncomingNotification(
        invite: IncomingInvite,
        mode: IncomingCallPresentationMode,
        bitmap: Bitmap?,
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
            val builder = incomingNotificationBuilder(invite, mode, answer, reject, fullScreen, bitmap)
            notification = builder.build()
            publish(requireNotNull(notification))
            enqueueIncomingPhotoRefresh(invite, mode, answer, reject, fullScreen, bitmap)
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
    ): Notification.Builder {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(
                context,
                if (mode == IncomingCallPresentationMode.InApp) InAppChannelId else ChannelId,
            )
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        bitmap?.let(builder::setLargeIcon)
        val personBuilder = if (Build.VERSION.SDK_INT >= 28) {
            Person.Builder()
                .setName(invite.caller.ifEmpty { "TiniTalk" })
                .setImportant(true)
                .also { person ->
                    if (Build.VERSION.SDK_INT >= 31) {
                        bitmap?.let { person.setIcon(Icon.createWithBitmap(it)) }
                    }
                }
        } else {
            null
        }
            @Suppress("DEPRECATION")
            builder
                .setSmallIcon(R.drawable.ic_call_ringing)
                .setContentTitle("Входящий звонок")
                .setContentText(
                    if (Build.VERSION.SDK_INT >= 31) "Входящий звонок" else invite.caller.ifEmpty { "TiniTalk" },
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
            if (mode == IncomingCallPresentationMode.FullScreen) {
                builder.setFullScreenIntent(fullScreen, true)
            }
            if (Build.VERSION.SDK_INT >= 31) {
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
        return builder
    }

    private fun enqueueIncomingPhotoRefresh(
        invite: IncomingInvite,
        mode: IncomingCallPresentationMode,
        answer: PendingIntent,
        reject: PendingIntent,
        fullScreen: PendingIntent,
        initialBitmap: Bitmap?,
    ) {
        val address = incomingPhotoAddress(invite) ?: return
        if (initialBitmap != null) return
        val revision = photoLoader.revision
        val requestKey = invite.owner.localId()
        photoLoader.load(address, requestKey, revision) { loadedKey, capturedRevision, bitmap ->
            if (loadedKey != requestKey || bitmap == null) return@load
            if (capturedRevision != photoLoader.revision) return@load
            val current = IncomingCallController().load(context)?.invite ?: return@load
            if (current.owner != invite.owner || !current.expiresAt.isAfter(Instant.now())) return@load
            val notification = incomingNotificationBuilder(current, mode, answer, reject, fullScreen, bitmap).build()
            context.getSystemService(NotificationManager::class.java).notify(NotificationId, notification)
        }
    }

    /** Hydrates only the compact persisted count map; contacts/history remain in memory. */
    internal fun syncMissedAccounts(accounts: Collection<AccountId>) {
        val store = AccountMissedBadgeStore(context.getSharedPreferences("tinitalk", Context.MODE_PRIVATE))
        AccountMissedBadges.syncPersisted(accounts, store::load, store::save)
    }

    internal fun beginAccountMissedCountRefresh(accountId: AccountId): AccountBadgeRefreshId? =
        AccountMissedBadges.beginRefresh(accountId)

    internal fun updateAccountMissedState(
        accountId: AccountId,
        unread: CallUnreadState,
        refreshId: AccountBadgeRefreshId?,
        latest: IncomingInvite? = null,
        redialBinding: CallSessionBinding? = latest?.sessionBinding,
        immediate: Boolean = false,
    ): AccountMissedBadgeUpdate {
        val store = AccountMissedBadgeStore(context.getSharedPreferences("tinitalk", Context.MODE_PRIVATE))
        val target = missedCallTarget(accountId, unread, latest, redialBinding)
        return if (immediate) {
            AccountMissedBadges.updateImmediately(
                accountId,
                refreshId,
                unread.unreadMissedCount,
                store::save,
                ::publishNewestMissedCount,
                target = target,
            )
        } else {
            AccountMissedBadges.update(
                accountId,
                refreshId,
                unread.unreadMissedCount,
                store::save,
                ::publishNewestMissedCount,
                target = target,
            )
        }
    }

    internal fun removeAccountMissedCount(accountId: AccountId) {
        val store = AccountMissedBadgeStore(context.getSharedPreferences("tinitalk", Context.MODE_PRIVATE))
        AccountMissedBadges.remove(accountId, store::save, ::publishNewestMissedCount)
    }

    /** Aggregate observer for the account-scoped counter; legacy observers must not drive multi-account UI. */
    internal fun observeAccountMissedCount(observer: (Int) -> Unit) = AccountMissedBadges.observe(observer)

    internal fun removeAccountMissedCountObserver(observer: (Int) -> Unit) = AccountMissedBadges.removeObserver(observer)

    internal fun showAccountMissedIfAbsent(accountId: AccountId, invite: IncomingInvite?) {
        val refreshId = beginAccountMissedCountRefresh(accountId)
        val current = AccountMissedBadges.snapshot()[accountId] ?: 0
        updateAccountMissedState(accountId, CallUnreadState(current.coerceAtLeast(1), emptyList()), refreshId, invite, immediate = true)
    }

    private fun missedCallTarget(
        accountId: AccountId,
        unread: CallUnreadState,
        latest: IncomingInvite?,
        redialBinding: CallSessionBinding?,
    ): MissedCallTarget? {
        val historyTarget = unread.unreadMissed
            .filter { it.peerLogin.isNotBlank() }
            .maxByOrNull { it.startedAt }
        val inviteTarget = latest?.takeIf { it.accountId == accountId }
        return MissedCallTarget(
            occurredAt = historyTarget?.startedAt
                ?: inviteTarget?.expiresAt?.minusSeconds(IncomingCallTtlSeconds)?.epochSecond
                ?: return null,
            accountId = accountId,
            latest = inviteTarget,
            latestUnread = historyTarget,
            redialBinding = redialBinding,
        )
    }

    private fun publishNewestMissedCount(count: Int) {
        val target = AccountMissedBadges.newestTarget()
        publishMissedCount(
            count = count,
            latest = target?.latest,
            latestUnreadLogin = target?.latestUnread?.peerLogin,
            latestUnreadName = target?.latestUnread?.peerName,
            accountId = target?.accountId,
            redialBinding = target?.redialBinding,
        )
    }

    private fun publishMissedCount(
        count: Int,
        latest: IncomingInvite?,
        latestUnreadLogin: String? = null,
        latestUnreadName: String? = null,
        accountId: AccountId? = latest?.accountId,
        redialBinding: CallSessionBinding? = latest?.sessionBinding,
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
        val redialName = latestUnreadName?.takeIf(String::isNotBlank)
            ?: matchingLatest?.caller?.takeIf(String::isNotBlank)
            ?: redialLogin
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
                redialName?.let { name ->
                    if (count == 1) name else "$count ${callsWord(count)} · $name"
                } ?: "$count ${missedCallsWord(count)}",
            )
            .setCategory(Notification.CATEGORY_MISSED_CALL)
            .setContentIntent(openApp)
            .setAutoCancel(false)
            .setOngoing(true)
            .setNumber(count)
            .setBadgeIconType(Notification.BADGE_ICON_SMALL)
            .setOnlyAlertOnce(true)
        redialBinding
            ?.takeIf { !it.sessionId.isNullOrBlank() }
            ?.takeIf {
                shouldOfferMissedRedial(
                    redialLogin,
                    accountId != null && (latest == null || latest.accountId == accountId),
                )
            }
            ?.let { binding ->
                val id = requireNotNull(accountId)
                val login = requireNotNull(redialLogin)
                val peer = AccountPeerKey(id, login)
                val redialKey = matchingLatest?.key ?: AccountCallKey(id, "missed:$login")
                val redialOwner = AccountCallOwner(redialKey, binding)
                val redial = PendingIntent.getActivity(
                    context,
                    redialOwner.localId().hashCode(),
                    CallActivity.redialIntent(
                        context,
                        peer,
                        redialName ?: login,
                        binding,
                    ),
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

    private fun incomingPhotoAddress(invite: IncomingInvite): ContactAddress? =
        invite.callerLogin?.takeIf(String::isNotBlank)
            ?.let { login -> ContactAddress.of(invite.sessionBinding.serverUrl, login) }

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
        IncomingRingtone.stop(invite.key)
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

    private fun callsWord(count: Int): String {
        val lastTwo = count % 100
        if (lastTwo in 11..14) return "звонков"
        return when (count % 10) {
            1 -> "звонок"
            2, 3, 4 -> "звонка"
            else -> "звонков"
        }
    }

    companion object {
        private const val ChannelId = "incoming_calls_v2"
        private const val InAppChannelId = "incoming_calls_in_app_v1"
        private const val MissedChannelId = "missed_calls_v2"
        internal const val NotificationId = 11
        private const val MissedNotificationId = 12
        private const val IncomingCallTtlSeconds = 30L
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
