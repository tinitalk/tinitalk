package org.tinitalk.push

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Build
import androidx.core.app.Person as CompatPerson
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.net.toUri
import org.tinitalk.CallActivity
import org.tinitalk.MainActivity
import org.tinitalk.R
import org.tinitalk.call.AccountCallKey
import org.tinitalk.call.AccountCallOwner
import org.tinitalk.contactOpenIntent
import org.tinitalk.contactPhotoNotificationLoader
import org.tinitalk.data.AccountId
import org.tinitalk.data.AccountPeerKey
import org.tinitalk.data.ContactAddress
import org.tinitalk.missed.MissedCallsRepository
import org.tinitalk.missed.MissedCallsSnapshot
import org.tinitalk.missed.MissedCallTarget
import java.security.MessageDigest

/** Renders missed-call state; never owns counts, persistence, or server-response generations. */
internal class MissedCallNotifier(
    private val context: Context,
    private val missedCalls: MissedCallsRepository,
    photoLoader: ContactPhotoNotificationLoader? = null,
) {
    private val photoLoader by lazy { photoLoader ?: contactPhotoNotificationLoader(context) }
    private val missedContactPlaceholder by lazy { missedContactPlaceholder(context) }

    fun render(snapshot: MissedCallsSnapshot) {
        val count = snapshot.totalCount
        ensureMissedChannel()
        val manager = context.getSystemService(NotificationManager::class.java)
        val reconcileAccounts = snapshot.reconcileAccounts
        if (count <= 0) {
            dismissAllMissedNotifications(manager)
            return
        }
        cancelInactiveAccountMissedChildren(manager, snapshot.counts.keys)
        val targets = snapshot.targets
            .sortedWith(compareByDescending<MissedCallTarget> { it.occurredAt }.thenBy { it.login })
        val desiredAccountTags = targets
            .groupBy(MissedCallTarget::accountId)
            .mapValues { (_, accountTargets) -> accountTargets.mapTo(mutableSetOf(), ::missedChildTag) }
        reconcileAccounts.forEach { accountId ->
            cancelStaleAccountMissedChildren(manager, accountId, desiredAccountTags[accountId].orEmpty())
        }
        targets.forEach { target -> publishMissedChild(manager, count, target) }
        publishMissedSummary(manager, count, targets)
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
        val builder = Notification.Builder(context, MissedChannelId)
        builder
            .setSmallIcon(R.drawable.ic_call_missed)
            .setContentTitle(redialName)
            .setContentText(missedTargetText(target.missedCount))
            .setCategory(if (Build.VERSION.SDK_INT >= 31) Notification.CATEGORY_MISSED_CALL else Notification.CATEGORY_CALL)
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
                val redialKey = AccountCallKey(id, target.callId ?: "missed:$login")
                val redialOwner = AccountCallOwner(redialKey, binding)
                val redial = PendingIntent.getActivity(
                    context,
                    redialOwner.localId().hashCode(),
                    CallActivity.redialIntent(
                        context,
                        peer,
                        redialName,
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
        val builder = Notification.Builder(context, MissedChannelId)
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
            .setCategory(if (Build.VERSION.SDK_INT >= 31) Notification.CATEGORY_MISSED_CALL else Notification.CATEGORY_CALL)
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
            missedCalls.withCurrentTarget(
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
        val serverUrl = target.serverUrl?.takeIf(String::isNotBlank) ?: return null
        return ContactAddress.of(serverUrl, target.login)
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
        .setData("tinitalk://missed/$target".toUri())
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    private fun ensureMissedChannel() {
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
        private const val MissedChannelId = "missed_calls_v2"
        private const val MissedNotificationGroupKey = "org.tinitalk.MISSED_CALLS"
        private const val MissedChildTagPrefix = "tt.missed.v2."
        private const val MissedSummaryNotificationId = 12
        private const val MissedChildNotificationId = 13
        private const val MaxMissedSummaryLines = 5
    }
}

internal fun shouldOfferMissedRedial(login: String?, hasAccountIdentity: Boolean): Boolean =
    !login.isNullOrBlank() && hasAccountIdentity
