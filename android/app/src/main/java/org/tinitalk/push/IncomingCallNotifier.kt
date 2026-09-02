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
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.Person as CompatPerson
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.graphics.drawable.IconCompat
import org.tinitalk.CallActivity
import org.tinitalk.MainActivity
import org.tinitalk.R
import org.tinitalk.contactOpenIntent
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
import java.security.MessageDigest
import java.time.Instant
import java.time.Duration
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
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
    @Synchronized fun currentUpdate(): AccountMissedBadgeUpdate =
        AccountMissedBadgeUpdate(true, counts.values.sum(), revision)
}

internal data class AccountMissedBadgeUpdate(val applied: Boolean, val count: Int, val revision: Long)
internal data class AccountBadgeRefreshId(val accountId: AccountId, val generation: Long)
internal data class MissedCallTarget(
    val occurredAt: Long,
    val accountId: AccountId,
    val latest: IncomingInvite?,
    val latestUnread: UnreadMissedContact?,
    val redialBinding: CallSessionBinding?,
    val missedCount: Int?,
) {
    val login: String
        get() = latestUnread?.peerLogin?.takeIf(String::isNotBlank)
            ?: requireNotNull(latest?.callerLogin?.takeIf(String::isNotBlank))
    val name: String?
        get() = latestUnread?.peerName?.takeIf(String::isNotBlank)
            ?: latest?.caller?.takeIf(String::isNotBlank)
}

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
    private val targets = mutableMapOf<AccountId, MutableMap<String, MissedCallTarget>>()
    private val pendingReconcileAccounts = linkedSetOf<AccountId>()

    fun sync(active: Collection<AccountId>, persisted: Map<AccountId, Int>): Int = synchronized(this) {
        markInactiveAccountsForReconcile(active, persisted.keys)
        retainActiveTargets(active)
        counter.sync(active, persisted).also(::notifyObservers)
    }
    fun syncPersisted(
        active: Collection<AccountId>,
        load: () -> Map<AccountId, Int>,
        save: (Map<AccountId, Int>) -> Unit,
        publish: ((Int) -> Unit)? = null,
    ): Int = synchronized(this) {
        val persisted = load()
        markInactiveAccountsForReconcile(active, persisted.keys)
        retainActiveTargets(active)
        counter.sync(active, persisted).also { count ->
            save(counter.snapshot())
            notifyObservers(count)
            publish?.let { callback ->
                val update = counter.currentUpdate()
                execute { publishIfCurrent(update, callback) }
            }
        }
    }
    fun beginRefresh(accountId: AccountId): AccountBadgeRefreshId? = counter.beginRefresh(accountId)
    fun observe(observer: (Int) -> Unit) { observers += observer; observer(counter.snapshot().values.sum()) }
    fun removeObserver(observer: (Int) -> Unit) { observers -= observer }
    fun remove(accountId: AccountId, persist: (Map<AccountId, Int>) -> Unit, publish: (Int) -> Unit) = synchronized(this) {
        pendingReconcileAccounts += accountId
        targets.remove(accountId)
        counter.remove(accountId).also { update -> persist(counter.snapshot()); notifyObservers(update.count); execute { publishIfCurrent(update, publish) } }
    }
    fun update(
        accountId: AccountId,
        refreshId: AccountBadgeRefreshId?,
        count: Int,
        persist: (Map<AccountId, Int>) -> Unit,
        publish: (Int) -> Unit,
        newTargets: List<MissedCallTarget> = emptyList(),
        authoritativeTargets: Boolean = false,
    ): AccountMissedBadgeUpdate = synchronized(this) {
        counter.update(accountId, refreshId, count).also { update ->
            if (update.applied) {
                pendingReconcileAccounts += accountId
                updateTargets(accountId, count, newTargets, authoritativeTargets)
                persist(counter.snapshot())
                notifyObservers(update.count)
                execute { publishIfCurrent(update, publish) }
            }
        }
    }
    fun updateImmediately(
        accountId: AccountId,
        refreshId: AccountBadgeRefreshId?,
        count: Int,
        persist: (Map<AccountId, Int>) -> Unit,
        publish: (Int) -> Unit,
        newTargets: List<MissedCallTarget> = emptyList(),
        authoritativeTargets: Boolean = false,
    ): AccountMissedBadgeUpdate = synchronized(this) {
        counter.update(accountId, refreshId, count).also { update ->
            if (update.applied) {
                pendingReconcileAccounts += accountId
                updateTargets(accountId, count, newTargets, authoritativeTargets)
                persist(counter.snapshot())
                notifyObservers(update.count)
                publish(update.count)
            }
        }
    }
    private fun updateTargets(
        accountId: AccountId,
        count: Int,
        newTargets: List<MissedCallTarget>,
        authoritativeTargets: Boolean,
    ) {
        if (count <= 0) {
            targets.remove(accountId)
            return
        }
        if (authoritativeTargets) {
            targets[accountId] = newTargets
                .filter { it.accountId == accountId }
                .associateByTo(linkedMapOf(), MissedCallTarget::login)
            return
        }
        if (newTargets.isEmpty()) return
        val accountTargets = targets.getOrPut(accountId, ::linkedMapOf)
        newTargets.filter { it.accountId == accountId }.forEach { candidate ->
            val current = accountTargets[candidate.login]
            if (current == null || candidate.occurredAt >= current.occurredAt) {
                accountTargets[candidate.login] = candidate.copy(
                    missedCount = listOfNotNull(candidate.missedCount, current?.missedCount).maxOrNull(),
                )
            }
        }
    }
    private fun markInactiveAccountsForReconcile(
        active: Collection<AccountId>,
        persistedAccounts: Collection<AccountId>,
    ) {
        val allowed = active.toSet()
        pendingReconcileAccounts += (targets.keys + counter.snapshot().keys + persistedAccounts) - allowed
    }
    private fun retainActiveTargets(active: Collection<AccountId>) = targets.keys.retainAll(active.toSet())
    fun targetsSnapshot(): List<MissedCallTarget> = synchronized(this) {
        targets.values.flatMap { it.values }.toList()
    }
    fun publishTargetIfCurrent(
        count: Int,
        accountId: AccountId,
        login: String,
        matches: (MissedCallTarget) -> Boolean,
        publish: (MissedCallTarget) -> Unit,
    ): Boolean = synchronized(this) {
        if (counter.snapshot().values.sum() != count) return@synchronized false
        val target = targets[accountId]?.get(login) ?: return@synchronized false
        if (!matches(target)) return@synchronized false
        publish(target)
        true
    }
    fun pendingReconcileAccounts(): Set<AccountId> = synchronized(this) {
        pendingReconcileAccounts.toSet()
    }
    fun markReconciled(accounts: Set<AccountId>) = synchronized(this) {
        pendingReconcileAccounts.removeAll(accounts)
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

private const val NotificationPhotoCornerRadiusFraction = 0.24f
private const val MissedContactPlaceholderPixels = 256
private const val MissedContactPlaceholderBackground = 0xFF0F172A.toInt()
private const val MissedContactPlaceholderForeground = 0xFFD4AF37.toInt()

internal fun roundedNotificationPhoto(source: Bitmap): Bitmap {
    val size = min(source.width, source.height)
    val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val halfSize = size / 2f
    val cornerRadius = size * NotificationPhotoCornerRadiusFraction
    val innerHalfSize = halfSize - cornerRadius
    val left = (source.width - size) / 2
    val top = (source.height - size) / 2
    val row = IntArray(size)
    for (y in 0 until size) {
        source.getPixels(row, 0, size, left, top + y, size, 1)
        for (x in 0 until size) {
            val dx = (abs(x + 0.5f - halfSize) - innerHalfSize).coerceAtLeast(0f)
            val dy = (abs(y + 0.5f - halfSize) - innerHalfSize).coerceAtLeast(0f)
            val coverage = (cornerRadius + 0.5f - sqrt(dx * dx + dy * dy)).coerceIn(0f, 1f)
            val alpha = Color.alpha(row[x])
            row[x] = if (coverage <= 0f || alpha == 0) {
                Color.TRANSPARENT
            } else {
                (row[x] and 0x00FFFFFF) or ((alpha * coverage).roundToInt() shl 24)
            }
        }
        output.setPixels(row, 0, size, 0, y, size, 1)
    }
    return output
}

internal fun missedContactPlaceholder(
    context: Context,
    size: Int = MissedContactPlaceholderPixels,
): Bitmap {
    require(size > 0) { "placeholder size must be positive" }
    val square = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
        eraseColor(MissedContactPlaceholderBackground)
    }
    val person = DrawableCompat.wrap(
        checkNotNull(ContextCompat.getDrawable(context, R.drawable.ic_person)),
    ).mutate()
    DrawableCompat.setTint(person, MissedContactPlaceholderForeground)
    person.setBounds(0, 0, size, size)
    person.draw(Canvas(square))
    return roundedNotificationPhoto(square).also { square.recycle() }
}

class IncomingCallNotifier(
    private val context: Context,
    photoLoader: ContactPhotoNotificationLoader? = null,
) {
    private val photoLoader: ContactPhotoNotificationLoader by lazy {
        photoLoader ?: contactPhotoNotificationLoader(context)
    }
    private val missedContactPlaceholder by lazy { missedContactPlaceholder(context) }

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
                .setBadgeIconType(Notification.BADGE_ICON_NONE)
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
        AccountMissedBadges.syncPersisted(accounts, store::load, store::save, ::publishMissedNotifications)
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
        val targets = missedCallTargets(accountId, unread, latest, redialBinding)
        val publish: (Int) -> Unit = ::publishMissedNotifications
        return if (immediate) {
            AccountMissedBadges.updateImmediately(
                accountId,
                refreshId,
                unread.unreadMissedCount,
                store::save,
                publish,
                newTargets = targets,
                authoritativeTargets = latest == null || unread.unreadMissed.isNotEmpty(),
            )
        } else {
            AccountMissedBadges.update(
                accountId,
                refreshId,
                unread.unreadMissedCount,
                store::save,
                publish,
                newTargets = targets,
                authoritativeTargets = true,
            )
        }
    }

    internal fun removeAccountMissedCount(accountId: AccountId) {
        val store = AccountMissedBadgeStore(context.getSharedPreferences("tinitalk", Context.MODE_PRIVATE))
        AccountMissedBadges.remove(accountId, store::save, ::publishMissedNotifications)
    }

    /** Aggregate observer for the account-scoped counter; legacy observers must not drive multi-account UI. */
    internal fun observeAccountMissedCount(observer: (Int) -> Unit) = AccountMissedBadges.observe(observer)

    internal fun removeAccountMissedCountObserver(observer: (Int) -> Unit) = AccountMissedBadges.removeObserver(observer)

    internal fun showAccountMissedIfAbsent(accountId: AccountId, invite: IncomingInvite?) {
        val refreshId = beginAccountMissedCountRefresh(accountId)
        val current = AccountMissedBadges.snapshot()[accountId] ?: 0
        updateAccountMissedState(accountId, CallUnreadState(current.coerceAtLeast(1), emptyList()), refreshId, invite, immediate = true)
    }

    private fun missedCallTargets(
        accountId: AccountId,
        unread: CallUnreadState,
        latest: IncomingInvite?,
        redialBinding: CallSessionBinding?,
    ): List<MissedCallTarget> {
        val inviteTarget = latest?.takeIf { invite ->
            invite.accountId == accountId && !invite.callerLogin.isNullOrBlank()
        }
        val historyByLogin = unread.unreadMissed
            .filter { it.peerLogin.isNotBlank() }
            .groupBy(UnreadMissedContact::peerLogin)
        val historyTargets = historyByLogin
            .mapNotNull { (_, entries) -> entries.maxByOrNull(UnreadMissedContact::startedAt) }
            .map { historyTarget ->
                val knownCount = historyTarget.missedCount?.takeIf { it > 0 }
                    ?: when {
                        historyByLogin.size == 1 -> unread.unreadMissedCount.coerceAtLeast(1)
                        unread.unreadMissedCount == historyByLogin.size -> 1
                        else -> null
                    }
                MissedCallTarget(
                    occurredAt = historyTarget.startedAt,
                    accountId = accountId,
                    latest = inviteTarget?.takeIf { it.callerLogin == historyTarget.peerLogin },
                    latestUnread = historyTarget,
                    redialBinding = redialBinding,
                    missedCount = knownCount,
                )
            }
        if (historyTargets.isNotEmpty()) return historyTargets
        return listOfNotNull(
            inviteTarget?.let { invite ->
                MissedCallTarget(
                    occurredAt = invite.expiresAt.minusSeconds(IncomingCallTtlSeconds).epochSecond,
                    accountId = accountId,
                    latest = invite,
                    latestUnread = null,
                    redialBinding = redialBinding,
                    missedCount = 1,
                )
            },
        )
    }

    private fun publishMissedNotifications(count: Int) {
        ensureMissedChannel()
        val manager = context.getSystemService(NotificationManager::class.java)
        val reconcileAccounts = AccountMissedBadges.pendingReconcileAccounts()
        if (count <= 0) {
            dismissAllMissedNotifications(manager)
            AccountMissedBadges.markReconciled(reconcileAccounts)
            return
        }
        cancelInactiveAccountMissedChildren(manager, AccountMissedBadges.snapshot().keys)
        val targets = AccountMissedBadges.targetsSnapshot()
            .sortedWith(compareByDescending<MissedCallTarget> { it.occurredAt }.thenBy { it.login })
        val desiredAccountTags = targets
            .groupBy(MissedCallTarget::accountId)
            .mapValues { (_, accountTargets) -> accountTargets.mapTo(mutableSetOf(), ::missedChildTag) }
        reconcileAccounts.forEach { accountId ->
            cancelStaleAccountMissedChildren(manager, accountId, desiredAccountTags[accountId].orEmpty())
        }
        targets.forEach { target -> publishMissedChild(manager, count, target) }
        publishMissedSummary(manager, count, targets)
        AccountMissedBadges.markReconciled(reconcileAccounts)
    }

    private fun publishMissedChild(
        manager: NotificationManager,
        totalCount: Int,
        target: MissedCallTarget,
        photoBitmap: Bitmap? = null,
        loadPhoto: Boolean = true,
    ) {
        val redialLogin = target.login
        val redialName = target.name ?: redialLogin
        val photoAddress = missedPhotoAddress(target)
        val sourcePhoto = photoBitmap ?: photoAddress?.let(photoLoader::peek)
        val notificationPhoto = sourcePhoto?.let(::roundedNotificationPhoto) ?: missedContactPlaceholder
        val childTag = missedChildTag(target)
        val openAppIntent = contactOpenIntent(
            context,
            AccountPeerKey(target.accountId, redialLogin),
            childTag,
        )
        val openApp = PendingIntent.getActivity(
            context,
            MissedChildNotificationId,
            openAppIntent,
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
            .setContentTitle(redialName)
            .setContentText(missedTargetText(target.missedCount))
            .setCategory(Notification.CATEGORY_MISSED_CALL)
            .setContentIntent(openApp)
            .setAutoCancel(false)
            .setOngoing(true)
            .setWhen(target.occurredAt * 1_000L)
            .setShowWhen(true)
            .setGroup(MissedNotificationGroupKey)
            .setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN)
            .setNumber(target.missedCount ?: 1)
            .setBadgeIconType(Notification.BADGE_ICON_SMALL)
            .setOnlyAlertOnce(true)
        target.redialBinding
            ?.takeIf { !it.sessionId.isNullOrBlank() }
            ?.takeIf { shouldOfferMissedRedial(redialLogin, true) }
            ?.let { binding ->
                val id = target.accountId
                val login = redialLogin
                val peer = AccountPeerKey(id, login)
                val redialKey = target.latest?.key ?: AccountCallKey(id, "missed:$login")
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
                        "Перезвонить",
                        redial,
                    ).build(),
                )
            }
        val usesConversationLayout = applyMissedConversationStyle(
            builder = builder,
            target = target,
            address = photoAddress,
            callerName = redialName,
            count = target.missedCount,
            photo = notificationPhoto,
            openAppIntent = openAppIntent,
        )
        if (!usesConversationLayout) builder.setLargeIcon(notificationPhoto)
        manager.notify(childTag, MissedChildNotificationId, builder.build())
        if (loadPhoto && sourcePhoto == null && photoAddress != null) {
            enqueueMissedPhotoRefresh(totalCount, target, photoAddress)
        }
    }

    private fun publishMissedSummary(
        manager: NotificationManager,
        count: Int,
        targets: List<MissedCallTarget>,
    ) {
        val contactCount = manager.activeNotifications.count { notification ->
            notification.id == MissedChildNotificationId &&
                notification.tag?.startsWith(MissedChildTagPrefix) == true
        }
        val displayCount = maxOf(
            count,
            contactCount,
            targets.sumOf { target -> target.missedCount ?: 1 },
        )
        val openAppIntent = missedOpenIntent("all")
        val openApp = PendingIntent.getActivity(
            context,
            MissedSummaryNotificationId,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, MissedChannelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        val style = Notification.InboxStyle()
        targets.take(MaxMissedSummaryLines).forEach { target ->
            style.addLine(
                when (val missedCount = target.missedCount) {
                    null -> "${target.name ?: target.login} — пропущенные звонки"
                    1 -> target.name ?: target.login
                    else -> "${target.name ?: target.login} — $missedCount ${callsWord(missedCount)}"
                },
            )
        }
        builder
            .setSmallIcon(R.drawable.ic_call_missed)
            .setContentTitle(if (displayCount == 1) "Пропущенный звонок" else "Пропущенные звонки")
            .setContentText(
                if (contactCount > 1) "$displayCount ${missedCallsWord(displayCount)} от $contactCount ${contactsWord(contactCount)}"
                else "$displayCount ${missedCallsWord(displayCount)}",
            )
            .setStyle(style.setSummaryText("$displayCount ${missedCallsWord(displayCount)}"))
            .setCategory(Notification.CATEGORY_MISSED_CALL)
            .setContentIntent(openApp)
            .setAutoCancel(false)
            .setOngoing(true)
            .setGroup(MissedNotificationGroupKey)
            .setGroupSummary(true)
            .setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN)
            .setNumber(displayCount)
            .setBadgeIconType(Notification.BADGE_ICON_SMALL)
            .setOnlyAlertOnce(true)
        manager.notify(MissedSummaryNotificationId, builder.build())
    }

    private fun applyMissedConversationStyle(
        builder: Notification.Builder,
        target: MissedCallTarget,
        address: ContactAddress?,
        callerName: String,
        count: Int?,
        photo: Bitmap,
        openAppIntent: Intent,
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val shortcutId = missedConversationShortcutId(target, address)
        val shortcutIcon = IconCompat.createWithBitmap(photo)
        val shortcutPerson = CompatPerson.Builder()
            .setName(callerName)
            .setKey(shortcutId)
            .setIcon(shortcutIcon)
            .setImportant(true)
            .build()
        val shortcut = ShortcutInfoCompat.Builder(context, shortcutId)
            .setShortLabel(callerName)
            .setLongLabel(callerName)
            .setIntent(openAppIntent)
            .setIcon(shortcutIcon)
            .setPerson(shortcutPerson)
            .setIsConversation()
            .build()
        val published = runCatching {
            ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
        }.getOrDefault(false)
        if (!published) return false

        val caller = Person.Builder()
            .setName(callerName)
            .setKey(shortcutId)
            .setIcon(Icon.createWithBitmap(photo))
            .setImportant(true)
            .build()
        val self = Person.Builder()
            .setName("TiniTalk")
            .setKey("tinitalk-self")
            .build()
        val message = missedTargetText(count)
        builder
            .setStyle(
                Notification.MessagingStyle(self)
                    .setGroupConversation(false)
                    .addMessage(message, System.currentTimeMillis(), caller),
            )
            .setShortcutId(shortcutId)
            .addPerson(caller)
        return true
    }

    private fun missedConversationShortcutId(target: MissedCallTarget, address: ContactAddress?): String {
        val identity = "${target.accountId.value}\u0000${address?.serverUrl.orEmpty()}\u0000${target.login}"
        return "missed_${stableMissedDigest(identity)}"
    }

    private fun enqueueMissedPhotoRefresh(
        count: Int,
        target: MissedCallTarget,
        address: ContactAddress,
    ) {
        val revision = photoLoader.revision
        val requestKey = "${missedChildTag(target)}:${target.occurredAt}:${target.missedCount}"
        photoLoader.load(address, requestKey, revision) { loadedKey, capturedRevision, bitmap ->
            if (loadedKey != requestKey || bitmap == null) return@load
            if (capturedRevision != photoLoader.revision) return@load
            AccountMissedBadges.publishTargetIfCurrent(
                count = count,
                accountId = target.accountId,
                login = target.login,
                matches = { currentTarget ->
                    currentTarget.occurredAt == target.occurredAt &&
                        currentTarget.missedCount == target.missedCount &&
                        missedPhotoAddress(currentTarget) == address
                },
            ) { currentTarget ->
                publishMissedChild(
                    manager = context.getSystemService(NotificationManager::class.java),
                    totalCount = count,
                    target = currentTarget,
                    photoBitmap = bitmap,
                    loadPhoto = false,
                )
            }
        }
    }

    private fun missedPhotoAddress(target: MissedCallTarget): ContactAddress? {
        return missedPhotoAddress(target.login, target.latest, target.redialBinding)
    }

    private fun missedChildTag(target: MissedCallTarget): String {
        val address = missedPhotoAddress(target)
        val contactIdentity = if (address != null) "${address.serverUrl}\u0000${address.login}" else target.login
        return missedAccountTagPrefix(target.accountId) + stableMissedDigest(contactIdentity)
    }

    private fun missedAccountTagPrefix(accountId: AccountId): String =
        "$MissedChildTagPrefix${stableMissedDigest(accountId.value)}:"

    private fun stableMissedDigest(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return buildString(24) {
            digest.take(12).forEach { byte ->
                val unsigned = byte.toInt() and 0xff
                append("0123456789abcdef"[unsigned ushr 4])
                append("0123456789abcdef"[unsigned and 0x0f])
            }
        }
    }

    private fun cancelStaleAccountMissedChildren(
        manager: NotificationManager,
        accountId: AccountId,
        desiredTags: Set<String>,
    ) {
        val accountPrefix = missedAccountTagPrefix(accountId)
        manager.activeNotifications
            .filter { it.id == MissedChildNotificationId && it.tag?.startsWith(accountPrefix) == true }
            .filterNot { it.tag in desiredTags }
            .forEach { manager.cancel(it.tag, it.id) }
    }

    private fun cancelInactiveAccountMissedChildren(
        manager: NotificationManager,
        activeAccounts: Collection<AccountId>,
    ) {
        val activePrefixes = activeAccounts.mapTo(mutableSetOf(), ::missedAccountTagPrefix)
        manager.activeNotifications
            .filter { active ->
                active.id == MissedChildNotificationId &&
                    active.tag?.startsWith(MissedChildTagPrefix) == true &&
                    activePrefixes.none { prefix -> active.tag?.startsWith(prefix) == true }
            }
            .forEach { manager.cancel(it.tag, it.id) }
    }

    private fun dismissAllMissedNotifications(manager: NotificationManager) {
        manager.activeNotifications
            .filter { it.id == MissedChildNotificationId && it.tag?.startsWith(MissedChildTagPrefix) == true }
            .forEach { manager.cancel(it.tag, it.id) }
        manager.cancel(MissedSummaryNotificationId)
    }

    private fun missedOpenIntent(target: String): Intent = Intent(context, MainActivity::class.java)
        .setAction(Intent.ACTION_VIEW)
        .setData(Uri.parse("tinitalk://missed/$target"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    private fun missedPhotoAddress(
        login: String?,
        latest: IncomingInvite?,
        redialBinding: CallSessionBinding?,
    ): ContactAddress? {
        val normalizedLogin = login?.takeIf(String::isNotBlank) ?: return null
        val serverUrl = redialBinding?.serverUrl?.takeIf(String::isNotBlank)
            ?: latest?.sessionBinding?.serverUrl?.takeIf(String::isNotBlank)
            ?: return null
        return ContactAddress.of(serverUrl, normalizedLogin)
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

    private fun missedTargetText(count: Int?): String = when (count) {
        null -> "Пропущенные звонки"
        1 -> "Пропущенный звонок"
        else -> "$count ${missedCallsWord(count)}"
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

    private fun contactsWord(count: Int): String {
        val lastTwo = count % 100
        if (lastTwo in 11..14) return "контактов"
        return when (count % 10) {
            1 -> "контакта"
            else -> "контактов"
        }
    }

    companion object {
        private const val ChannelId = "incoming_calls_v2"
        private const val InAppChannelId = "incoming_calls_in_app_v1"
        private const val MissedChannelId = "missed_calls_v2"
        private const val MissedNotificationGroupKey = "org.tinitalk.MISSED_CALLS"
        private const val MissedChildTagPrefix = "tt.missed.v2."
        internal const val NotificationId = 11
        private const val MissedSummaryNotificationId = 12
        private const val MissedChildNotificationId = 13
        private const val MaxMissedSummaryLines = 5
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
