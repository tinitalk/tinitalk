package org.tinitalk.telecom

import android.app.PendingIntent
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import org.tinitalk.CallActivity
import org.tinitalk.call.AccountCallKey
import org.tinitalk.call.AccountCallOwner
import org.tinitalk.call.CallAdmissionAttempt
import org.tinitalk.call.CallAdmissionGateway
import org.tinitalk.call.CallSessionBinding
import org.tinitalk.call.GlobalCallAdmission
import org.tinitalk.call.CallPhase
import org.tinitalk.call.CallServiceState
import org.tinitalk.data.AccountId
import org.tinitalk.push.IncomingCallForegroundService
import org.tinitalk.push.IncomingCallNotifier
import org.tinitalk.push.IncomingInvite
import java.time.Instant

class IncomingCallController internal constructor(
    private val telecomController: (Context) -> TelecomCallController = { context ->
        TelecomCallController(AndroidTelecomRegistrar(context))
    },
    private val admission: CallAdmissionGateway,
) {
    constructor(
        telecomController: (Context) -> TelecomCallController = { context ->
            TelecomCallController(AndroidTelecomRegistrar(context))
        },
    ) : this(telecomController, GlobalCallAdmission)

    internal constructor(admission: CallAdmissionGateway) : this(
        { context -> TelecomCallController(AndroidTelecomRegistrar(context)) },
        admission,
    )

    internal fun save(context: Context, invite: IncomingInvite, action: String? = null) =
        synchronized(PresentationLock) {
            synchronized(PendingActionLock) {
                prefs(context).edit()
                    .putString(ExtraAccountId, invite.accountId.value)
                    .putString(ExtraCallId, invite.callId)
                    .putString(ExtraServerUrl, invite.sessionBinding.serverUrl)
                    .putString(ExtraSessionLogin, invite.sessionBinding.login)
                    .putString(ExtraSessionId, invite.sessionBinding.sessionId)
                    .putString(ExtraConfigId, invite.sessionBinding.configId)
                    .putString(ExtraCaller, invite.caller)
                    .putString(ExtraCallerLogin, invite.callerLogin)
                    .putString(ExtraExpiresAt, invite.expiresAt.toString())
                    .putLong(ExtraLastSeq, invite.lastSeq)
                    .putString(ExtraAction, action)
                    .apply()
            }
        }

    fun clear(context: Context, owner: AccountCallOwner): Boolean = synchronized(PresentationLock) {
        synchronized(PendingActionLock) {
            val pending = load(context) ?: return@synchronized false
            if (pending.invite.owner != owner) return@synchronized false
            prefs(context).edit().clear().apply()
            admission.releaseStaged(owner)
            true
        }
    }

    fun finishTerminalPresentation(
        context: Context,
        owner: AccountCallOwner?,
        cancelPresentation: () -> Unit,
    ): Boolean = finishTerminalPresentation(context, owner, releaseReserved = true, cancelPresentation)

    internal fun handoffTerminalPresentation(
        context: Context,
        owner: AccountCallOwner?,
        cancelPresentation: () -> Unit,
    ): Boolean = finishTerminalPresentation(context, owner, releaseReserved = false, cancelPresentation)

    private fun finishTerminalPresentation(
        context: Context,
        owner: AccountCallOwner?,
        releaseReserved: Boolean,
        cancelPresentation: () -> Unit,
    ): Boolean = synchronized(PresentationLock) {
        val shouldCancel = synchronized(PendingActionLock) pending@{
            owner ?: return@pending false
            val pendingCall = load(context)
            val current = admission.current()?.owner
            val currentMatches = current == owner
            if (pendingCall?.invite?.owner != owner && !currentMatches) {
                if (pendingCall?.invite?.key != owner.key && current?.key != owner.key) {
                    rememberTerminal(context, owner)
                }
                return@pending false
            }
            rememberTerminal(context, owner)
            if (pendingCall?.invite?.owner == owner) {
                prefs(context).edit().clear().apply()
                if (releaseReserved) admission.releaseStaged(pendingCall.invite.owner)
            }
            true
        }
        if (shouldCancel) cancelPresentation()
        shouldCancel
    }

    internal fun reclaimPending(
        context: Context,
        now: Instant = Instant.now(),
        isCurrent: (org.tinitalk.call.AccountCallOwner) -> Boolean = { true },
    ): AccountCallKey? = synchronized(PresentationLock) {
        pruneExpiredPending(context, now)
        val candidate = synchronized(PendingActionLock) { load(context) } ?: return@synchronized null
        if (!isCurrent(candidate.invite.owner)) {
            synchronized(PendingActionLock) {
                val pending = load(context)
                if (pending?.invite?.owner == candidate.invite.owner) {
                    rememberTerminal(context, pending.invite.owner)
                    prefs(context).edit().clear().apply()
                    admission.releaseStaged(pending.invite.owner)
                }
            }
            return@synchronized null
        }
        synchronized(PendingActionLock) pending@{
            val pending = load(context) ?: return@pending null
            if (pending.invite.owner != candidate.invite.owner) return@pending null
            when (val attempt = admission.stage(pending.invite.owner)) {
                is CallAdmissionAttempt.Acquired,
                is CallAdmissionAttempt.Existing -> pending.invite.key
                is CallAdmissionAttempt.Busy -> {
                    rememberTerminal(context, pending.invite.owner)
                    prefs(context).edit().clear().apply()
                    null
                }
            }
        }
    }

    internal fun admitIncoming(
        context: Context,
        invite: IncomingInvite,
        now: Instant = Instant.now(),
    ): IncomingAdmissionResult = synchronized(PresentationLock) {
        pruneExpiredPending(context, now)
        synchronized(PendingActionLock) admission@{
            if (!invite.expiresAt.isAfter(now) ||
                isTerminal(context, invite.owner) ||
                isBusyRejected(context, invite.owner)
            ) {
                return@admission IncomingAdmissionResult.Invalid
            }
            val restored = load(context)
            if (restored != null) admission.stage(restored.invite.owner)
            val attempt = admission.stage(invite.owner)
            when (attempt) {
                is CallAdmissionAttempt.Busy -> {
                    rememberBusyRejected(context, invite.owner)
                    restored?.takeIf { it.invite.owner == invite.owner }?.let { pending ->
                        prefs(context).edit().clear().apply()
                        admission.releaseStaged(pending.invite.owner)
                    }
                    return@admission IncomingAdmissionResult.Busy
                }
                is CallAdmissionAttempt.Existing -> {
                    if (attempt.state == org.tinitalk.call.CallAdmissionState.Running) {
                        return@admission IncomingAdmissionResult.Duplicate
                    }
                }
                is CallAdmissionAttempt.Acquired -> Unit
            }
            val action = restored?.takeIf { it.invite.owner == invite.owner }?.action
            save(context, invite, action)
            if (attempt is CallAdmissionAttempt.Existing) {
                IncomingAdmissionResult.Duplicate
            } else {
                IncomingAdmissionResult.Admitted
            }
        }
    }

    internal fun presentSavedIncoming(
        context: Context,
        invite: IncomingInvite,
        now: Instant = Instant.now(),
        present: () -> Unit,
    ): Boolean = synchronized(PresentationLock) {
        pruneExpiredPending(context, now)
        val valid = synchronized(PendingActionLock) {
            val pending = load(context)
            pending?.invite?.owner == invite.owner &&
                !isTerminal(context, invite.owner) &&
                admission.current()?.let { it.owner == invite.owner && it.state == org.tinitalk.call.CallAdmissionState.Reserved } == true
        }
        if (valid) present()
        valid
    }

    internal fun expirePending(
        context: Context,
        owner: AccountCallOwner,
        now: Instant = Instant.now(),
        cancelPresentation: () -> Unit = {},
    ): Boolean = synchronized(PresentationLock) {
        val expired = synchronized(PendingActionLock) {
            val pending = load(context) ?: return@synchronized false
            if (pending.invite.owner != owner || pending.invite.expiresAt.isAfter(now)) return@synchronized false
            rememberTerminal(context, owner)
            prefs(context).edit().clear().apply()
            admission.releaseStaged(pending.invite.owner)
            true
        }
        if (expired) cancelPresentation()
        expired
    }

    internal fun pruneExpiredPending(context: Context, now: Instant = Instant.now()): AccountCallKey? =
        synchronized(PresentationLock) {
            val owner = synchronized(PendingActionLock) {
                load(context)?.invite?.takeUnless { it.expiresAt.isAfter(now) }?.owner
            } ?: return@synchronized null
            val expired = expirePending(context, owner, now) {
                IncomingCallForegroundService.stop(context)
                IncomingCallNotifier(context).cancel()
                runCatching { telecomController(context).cancel(owner.key) }
            }
            owner.key.takeIf { expired }
        }

    internal fun ownsIncoming(
        context: Context,
        invite: IncomingInvite,
        now: Instant = Instant.now(),
    ): Boolean = synchronized(PresentationLock) {
        synchronized(PendingActionLock) {
            if (!invite.expiresAt.isAfter(now) || isTerminal(context, invite.owner)) return@synchronized false
            val current = admission.current() ?: return@synchronized false
            current.owner == invite.owner &&
                (current.state == org.tinitalk.call.CallAdmissionState.Running || load(context)?.invite?.owner == invite.owner)
        }
    }

    internal fun removeAccount(
        context: Context,
        accountId: AccountId,
        binding: CallSessionBinding,
    ): AccountCallKey? = synchronized(PresentationLock) {
        val removed = synchronized(PendingActionLock) {
            val pending = load(context) ?: return@synchronized null
            if (!pending.invite.owner.matchesRemoval(accountId, binding)) return@synchronized null
            rememberTerminal(context, pending.invite.owner)
            prefs(context).edit().clear().apply()
            admission.releaseStaged(pending.invite.owner)
            pending.invite.key
        } ?: return@synchronized null
        IncomingCallForegroundService.stop(context)
        IncomingCallNotifier(context).cancel()
        runCatching { telecomController(context).cancel(removed) }
        removed
    }

    fun rememberTerminal(context: Context, owner: AccountCallOwner, nowMillis: Long = System.currentTimeMillis()) =
        synchronized(PresentationLock) {
            synchronized(PendingActionLock) {
                val prefs = terminalPrefs(context)
                val updated = TerminalCallTombstones.remember(
                    prefs.getStringSet(TerminalEntries, emptySet()).orEmpty(),
                    owner,
                    nowMillis,
                )
                prefs.edit().putStringSet(TerminalEntries, updated).apply()
            }
        }

    internal fun rememberTerminalIfCompatible(context: Context, owner: AccountCallOwner): Boolean =
        synchronized(PresentationLock) {
            synchronized(PendingActionLock) {
                val pendingOwner = load(context)?.invite?.owner
                val currentOwner = admission.current()?.owner
                val conflictsWithReplacement =
                    (pendingOwner?.key == owner.key && pendingOwner != owner) ||
                        (currentOwner?.key == owner.key && currentOwner != owner)
                if (conflictsWithReplacement) return@synchronized false
                rememberTerminal(context, owner)
                true
            }
        }

    fun isTerminal(context: Context, owner: AccountCallOwner, nowMillis: Long = System.currentTimeMillis()): Boolean =
        synchronized(PresentationLock) {
            synchronized(PendingActionLock) {
                val prefs = terminalPrefs(context)
                val stored = prefs.getStringSet(TerminalEntries, emptySet()).orEmpty()
                val pruned = TerminalCallTombstones.prune(stored, nowMillis)
                if (pruned != stored) prefs.edit().putStringSet(TerminalEntries, pruned).apply()
                TerminalCallTombstones.contains(pruned, owner, nowMillis)
            }
        }

    private fun rememberBusyRejected(
        context: Context,
        owner: AccountCallOwner,
        nowMillis: Long = System.currentTimeMillis(),
    ) = synchronized(PendingActionLock) {
        val prefs = busyPrefs(context)
        val updated = BusyCallTombstones.remember(
            prefs.getStringSet(BusyEntries, emptySet()).orEmpty(),
            owner,
            nowMillis,
        )
        prefs.edit().putStringSet(BusyEntries, updated).apply()
    }

    private fun isBusyRejected(
        context: Context,
        owner: AccountCallOwner,
        nowMillis: Long = System.currentTimeMillis(),
    ): Boolean = synchronized(PendingActionLock) {
        val prefs = busyPrefs(context)
        val stored = prefs.getStringSet(BusyEntries, emptySet()).orEmpty()
        val pruned = BusyCallTombstones.prune(stored, nowMillis)
        if (pruned != stored) prefs.edit().putStringSet(BusyEntries, pruned).apply()
        BusyCallTombstones.contains(pruned, owner, nowMillis)
    }

    fun load(context: Context): PendingIncomingCall? {
        val prefs = prefs(context)
        val accountId = prefs.getString(ExtraAccountId, null)?.takeIf(String::isNotBlank)?.let(::AccountId)
            ?: return null
        val callId = prefs.getString(ExtraCallId, null) ?: return null
        val serverUrl = prefs.getString(ExtraServerUrl, null)?.takeIf(String::isNotBlank) ?: return null
        val sessionLogin = prefs.getString(ExtraSessionLogin, null)?.takeIf(String::isNotBlank) ?: return null
        val expiresAt = prefs.getString(ExtraExpiresAt, null)?.let { runCatching { Instant.parse(it) }.getOrNull() }
            ?: return null
        return PendingIncomingCall(
            IncomingInvite(
                accountId = accountId,
                sessionBinding = CallSessionBinding(
                    serverUrl = serverUrl,
                    login = sessionLogin,
                    sessionId = prefs.getString(ExtraSessionId, null),
                    configId = prefs.getString(ExtraConfigId, null),
                ),
                callId = callId,
                caller = prefs.getString(ExtraCaller, "").orEmpty(),
                expiresAt = expiresAt,
                callerLogin = prefs.getString(ExtraCallerLogin, null),
                lastSeq = prefs.getLong(ExtraLastSeq, 0),
            ),
            prefs.getString(ExtraAction, null),
        )
    }

    internal fun claimAnswer(
        context: Context,
        invite: IncomingInvite,
        now: Instant = Instant.now(),
    ): IncomingAnswerClaim = synchronized(PresentationLock) {
        synchronized(PendingActionLock) {
            if (!invite.expiresAt.isAfter(now) || isTerminal(context, invite.owner)) {
                return@synchronized IncomingAnswerClaim.Invalid
            }
            val pending = load(context)
            if (pending?.invite?.owner != invite.owner || !pending.invite.expiresAt.isAfter(now)) {
                return@synchronized IncomingAnswerClaim.Invalid
            }
            if (pending.action == ActionAnswer) return@synchronized IncomingAnswerClaim.AlreadyClaimed
            prefs(context).edit().putString(ExtraAction, ActionAnswer).apply()
            IncomingAnswerClaim.Claimed
        }
    }

    fun activityIntent(context: Context, action: String, invite: IncomingInvite): PendingIntent =
        PendingIntent.getActivity(
            context,
            invite.key.localId().hashCode(),
            callActivityIntent(context, action, invite),
            pendingFlags(),
            activityOptions(),
        )

    fun openScreen(context: Context, invite: IncomingInvite) {
        runCatching {
            context.startActivity(callActivityIntent(context, ActionIncoming, invite))
        }
    }

    fun presentationIntent(context: Context, invite: IncomingInvite): Intent =
        intent(context, IncomingCallForegroundService::class.java, IncomingCallForegroundService.ActionShow, invite)

    fun actionIntent(context: Context, action: String, invite: IncomingInvite): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            (invite.key.localId() + action).hashCode(),
            intent(context, CallActionReceiver::class.java, action, invite),
            pendingFlags(),
        )

    fun answer(context: Context, invite: IncomingInvite, onComplete: () -> Unit = {}) =
        synchronized(PresentationLock) {
            if (!ownsIncoming(context, invite)) {
                onComplete()
                return@synchronized
            }
            try {
                // The user accepted in TiniTalk. Legacy Telecom can fail or never answer its callback,
                // so the real call must start immediately and Telecom is synchronized only best-effort.
                answerFromTelecom(context, invite)
            } finally {
                onComplete()
            }
            runCatching { telecomController(context).answer(invite.key) }
        }

    fun answerFromTelecom(context: Context, invite: IncomingInvite) = synchronized(PresentationLock) {
        val call = CallServiceState.snapshot()
        if (call.callKey == invite.key && call.phase != CallPhase.Idle && call.phase != CallPhase.Ended) return@synchronized
        if (!ownsIncoming(context, invite)) return@synchronized
        if (claimAnswer(context, invite) == IncomingAnswerClaim.Invalid) {
            finishTerminalPresentation(context, invite.owner) {
                IncomingCallNotifier(context).cancel()
            }
            telecomController(context).cancel(invite.key)
            return@synchronized
        }
        CallForegroundService.start(
            context,
            intent(context, CallForegroundService::class.java, CallForegroundService.ActionAnswer, invite),
        )
    }

    fun reject(context: Context, invite: IncomingInvite) = synchronized(PresentationLock) {
        if (!ownsIncoming(context, invite)) return@synchronized
        telecomController(context).reject(invite.key)
        rejectFromTelecom(context, invite)
    }

    fun rejectFromTelecom(context: Context, invite: IncomingInvite) = synchronized(PresentationLock) {
        if (!ownsIncoming(context, invite)) return@synchronized
        val alreadyTerminal = isTerminal(context, invite.owner)
        handoffIncoming(context, invite)
        if (alreadyTerminal) return@synchronized
        startTerminalService(context, invite, CallForegroundService.ActionReject)
    }

    fun disconnectFromTelecom(context: Context, invite: IncomingInvite) = synchronized(PresentationLock) {
        val ownsAdmission = admission.current()?.owner == invite.owner
        if (!ownsAdmission) return@synchronized
        val alreadyTerminal = isTerminal(context, invite.owner)
        val call = CallServiceState.snapshot()
        val liveSameCall = call.callKey == invite.key &&
            call.phase != CallPhase.Idle && call.phase != CallPhase.Ended
        handoffIncoming(context, invite)
        if (alreadyTerminal && !liveSameCall) return@synchronized
        startTerminalService(context, invite, CallForegroundService.ActionDisconnect)
    }

    private fun handoffIncoming(context: Context, invite: IncomingInvite) {
        handoffTerminalPresentation(context, invite.owner) {
            IncomingCallNotifier(context).cancel()
        }
    }

    private fun startTerminalService(context: Context, invite: IncomingInvite, action: String) {
        runCatching {
            CallForegroundService.start(
                context,
                intent(context, CallForegroundService::class.java, action, invite),
            )
        }.onFailure {
            admission.releaseStaged(invite.owner)
        }
    }

    companion object {
        const val ActionIncoming = "org.tinitalk.action.INCOMING_CALL"
        const val ActionAnswer = "org.tinitalk.action.ANSWER_CALL"
        const val ActionReject = "org.tinitalk.action.REJECT_CALL"

        private const val Store = "incoming_call"
        private const val TerminalStore = "terminal_calls"
        private const val TerminalEntries = "entries"
        private const val BusyStore = "busy_calls"
        private const val BusyEntries = "entries"
        private const val ExtraAccountId = "account_id"
        private const val ExtraCallId = "call_id"
        private const val ExtraServerUrl = "session_server_url"
        private const val ExtraSessionLogin = "session_login"
        private const val ExtraSessionId = "session_id"
        private const val ExtraConfigId = "config_id"
        private const val ExtraCaller = "caller"
        private const val ExtraCallerLogin = "caller_login"
        private const val ExtraExpiresAt = "expires_at"
        private const val ExtraLastSeq = "last_seq"
        private const val ExtraAction = "action"
        private val PresentationLock = Any()
        private val PendingActionLock = Any()

        fun inviteFrom(intent: Intent?): IncomingInvite? {
            val callId = intent?.getStringExtra(ExtraCallId) ?: return null
            val accountId = intent.getStringExtra(ExtraAccountId)?.takeIf(String::isNotBlank)?.let(::AccountId)
                ?: return null
            val serverUrl = intent.getStringExtra(ExtraServerUrl)?.takeIf(String::isNotBlank) ?: return null
            val sessionLogin = intent.getStringExtra(ExtraSessionLogin)?.takeIf(String::isNotBlank) ?: return null
            val expiresAt = intent.getStringExtra(ExtraExpiresAt)?.let { runCatching { Instant.parse(it) }.getOrNull() }
                ?: return null
            return IncomingInvite(
                accountId = accountId,
                sessionBinding = CallSessionBinding(
                    serverUrl = serverUrl,
                    login = sessionLogin,
                    sessionId = intent.getStringExtra(ExtraSessionId),
                    configId = intent.getStringExtra(ExtraConfigId),
                ),
                callId = callId,
                caller = intent.getStringExtra(ExtraCaller).orEmpty(),
                expiresAt = expiresAt,
                callerLogin = intent.getStringExtra(ExtraCallerLogin),
                lastSeq = intent.getLongExtra(ExtraLastSeq, 0),
            )
        }

        private fun prefs(context: Context) =
            context.getSharedPreferences(Store, Context.MODE_PRIVATE)

        private fun terminalPrefs(context: Context) =
            context.getSharedPreferences(TerminalStore, Context.MODE_PRIVATE)

        private fun busyPrefs(context: Context) =
            context.getSharedPreferences(BusyStore, Context.MODE_PRIVATE)

        private fun pendingFlags() = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        private fun activityOptions(): Bundle? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
            return ActivityOptions.makeBasic()
                .setPendingIntentCreatorBackgroundActivityStartMode(backgroundStartMode())
                .toBundle()
        }

        @Suppress("DEPRECATION")
        private fun backgroundStartMode(): Int =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
            } else {
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            }

        private fun intent(context: Context, target: Class<*>, action: String, invite: IncomingInvite): Intent =
            Intent(context, target)
                .setAction(action)
                .setData(Uri.parse("tinitalk://call/${Uri.encode(invite.owner.localId())}/${Uri.encode(action)}"))
                .putExtra(ExtraAccountId, invite.accountId.value)
                .putExtra(ExtraCallId, invite.callId)
                .putExtra(ExtraServerUrl, invite.sessionBinding.serverUrl)
                .putExtra(ExtraSessionLogin, invite.sessionBinding.login)
                .putExtra(ExtraSessionId, invite.sessionBinding.sessionId)
                .putExtra(ExtraConfigId, invite.sessionBinding.configId)
                .putExtra(ExtraCaller, invite.caller)
                .putExtra(ExtraCallerLogin, invite.callerLogin)
                .putExtra(ExtraExpiresAt, invite.expiresAt.toString())
                .putExtra(ExtraLastSeq, invite.lastSeq)

        private fun callActivityIntent(context: Context, action: String, invite: IncomingInvite): Intent =
            intent(context, CallActivity::class.java, action, invite)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_NO_USER_ACTION,
                )
    }
}

internal object TerminalCallTombstones {
    private const val TtlMillis = 120_000L
    private const val Limit = 16
    private const val Separator = '\t'

    fun remember(stored: Set<String>, owner: AccountCallOwner, nowMillis: Long): Set<String> {
        val entries = decode(stored, nowMillis).associateByTo(mutableMapOf()) { it.ownerId }
        val ownerId = owner.localId()
        entries[ownerId] = Entry(ownerId, nowMillis + TtlMillis)
        return encode(entries.values.sortedByDescending { it.expiresAtMillis }.take(Limit))
    }

    fun contains(stored: Set<String>, owner: AccountCallOwner, nowMillis: Long): Boolean =
        decode(stored, nowMillis).any { it.ownerId == owner.localId() }

    fun prune(stored: Set<String>, nowMillis: Long): Set<String> = encode(decode(stored, nowMillis))

    private fun decode(stored: Set<String>, nowMillis: Long): List<Entry> = stored.mapNotNull { value ->
        val expiresAt = value.substringBefore(Separator).toLongOrNull() ?: return@mapNotNull null
        val ownerId = value.substringAfter(Separator, "").takeIf(String::isNotEmpty) ?: return@mapNotNull null
        if (expiresAt <= nowMillis) null else Entry(ownerId, expiresAt)
    }

    private fun encode(entries: Collection<Entry>): Set<String> =
        entries.mapTo(linkedSetOf()) { "${it.expiresAtMillis}$Separator${it.ownerId}" }

    private data class Entry(val ownerId: String, val expiresAtMillis: Long)
}

internal object BusyCallTombstones {
    private const val TtlMillis = 120_000L
    private const val Limit = 16
    private const val Separator = '\t'

    fun remember(stored: Set<String>, owner: AccountCallOwner, nowMillis: Long): Set<String> {
        val entries = decode(stored, nowMillis).associateByTo(mutableMapOf()) { it.ownerId }
        val ownerId = owner.localId()
        entries[ownerId] = Entry(ownerId, nowMillis + TtlMillis)
        return encode(entries.values.sortedByDescending { it.expiresAtMillis }.take(Limit))
    }

    fun contains(stored: Set<String>, owner: AccountCallOwner, nowMillis: Long): Boolean =
        decode(stored, nowMillis).any { it.ownerId == owner.localId() }

    fun prune(stored: Set<String>, nowMillis: Long): Set<String> = encode(decode(stored, nowMillis))

    private fun decode(stored: Set<String>, nowMillis: Long): List<Entry> = stored.mapNotNull { value ->
        val expiresAt = value.substringBefore(Separator).toLongOrNull() ?: return@mapNotNull null
        val ownerId = value.substringAfter(Separator, "").takeIf(String::isNotEmpty) ?: return@mapNotNull null
        if (expiresAt <= nowMillis) null else Entry(ownerId, expiresAt)
    }

    private fun encode(entries: Collection<Entry>): Set<String> =
        entries.mapTo(linkedSetOf()) { "${it.expiresAtMillis}$Separator${it.ownerId}" }

    private data class Entry(val ownerId: String, val expiresAtMillis: Long)
}

data class PendingIncomingCall(val invite: IncomingInvite, val action: String?)

internal enum class IncomingAdmissionResult {
    Admitted,
    Duplicate,
    Busy,
    Invalid,
}

internal enum class IncomingAnswerClaim {
    Claimed,
    AlreadyClaimed,
    Invalid,
}
