package org.tinitalk.telecom

import android.app.PendingIntent
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import org.tinitalk.CallActivity
import org.tinitalk.call.CallPhase
import org.tinitalk.call.CallServiceState
import org.tinitalk.push.IncomingCallForegroundService
import org.tinitalk.push.IncomingCallNotifier
import org.tinitalk.push.IncomingInvite
import java.time.Instant

class IncomingCallController {
    fun save(context: Context, invite: IncomingInvite, action: String? = null) =
        synchronized(PendingActionLock) {
            prefs(context).edit()
                .putString(ExtraCallId, invite.callId)
                .putString(ExtraCaller, invite.caller)
                .putString(ExtraCallerLogin, invite.callerLogin)
                .putString(ExtraExpiresAt, invite.expiresAt.toString())
                .putLong(ExtraLastSeq, invite.lastSeq)
                .putString(ExtraAction, action)
                .apply()
        }

    fun clear(context: Context, callId: String): Boolean = synchronized(PendingActionLock) {
        val pending = load(context) ?: return false
        if (pending.invite.callId != callId) return false
        prefs(context).edit().clear().apply()
        return true
    }

    fun finishTerminalPresentation(
        context: Context,
        callId: String?,
        cancelPresentation: () -> Unit,
    ): Boolean = synchronized(PendingActionLock) {
        callId?.let { rememberTerminal(context, it) }
        val pending = load(context)
        if (pending != null && pending.invite.callId != callId) return false
        if (pending != null) prefs(context).edit().clear().apply()
        cancelPresentation()
        true
    }

    fun presentIncoming(
        context: Context,
        invite: IncomingInvite,
        present: () -> Unit,
    ): Boolean = synchronized(PendingActionLock) {
        if (!invite.expiresAt.isAfter(Instant.now()) || isTerminal(context, invite.callId)) return false
        val action = load(context)
            ?.takeIf { it.invite.callId == invite.callId }
            ?.action
        save(context, invite, action)
        present()
        true
    }

    fun rememberTerminal(context: Context, callId: String, nowMillis: Long = System.currentTimeMillis()) =
        synchronized(PendingActionLock) {
            val prefs = terminalPrefs(context)
            val updated = TerminalCallTombstones.remember(
                prefs.getStringSet(TerminalEntries, emptySet()).orEmpty(),
                callId,
                nowMillis,
            )
            prefs.edit().putStringSet(TerminalEntries, updated).apply()
        }

    fun isTerminal(context: Context, callId: String, nowMillis: Long = System.currentTimeMillis()): Boolean =
        synchronized(PendingActionLock) {
            val prefs = terminalPrefs(context)
            val stored = prefs.getStringSet(TerminalEntries, emptySet()).orEmpty()
            val pruned = TerminalCallTombstones.prune(stored, nowMillis)
            if (pruned != stored) prefs.edit().putStringSet(TerminalEntries, pruned).apply()
            TerminalCallTombstones.contains(pruned, callId, nowMillis)
        }

    fun load(context: Context): PendingIncomingCall? {
        val prefs = prefs(context)
        val callId = prefs.getString(ExtraCallId, null) ?: return null
        val expiresAt = prefs.getString(ExtraExpiresAt, null)?.let { runCatching { Instant.parse(it) }.getOrNull() }
            ?: return null
        return PendingIncomingCall(
            IncomingInvite(
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
    ): IncomingAnswerClaim = synchronized(PendingActionLock) {
        if (!invite.expiresAt.isAfter(now) || isTerminal(context, invite.callId)) {
            return@synchronized IncomingAnswerClaim.Invalid
        }
        val pending = load(context)
        if (pending?.invite?.callId != invite.callId || !pending.invite.expiresAt.isAfter(now)) {
            return@synchronized IncomingAnswerClaim.Invalid
        }
        if (pending.action == ActionAnswer) return@synchronized IncomingAnswerClaim.AlreadyClaimed
        prefs(context).edit().putString(ExtraAction, ActionAnswer).apply()
        IncomingAnswerClaim.Claimed
    }

    fun activityIntent(context: Context, action: String, invite: IncomingInvite): PendingIntent =
        PendingIntent.getActivity(
            context,
            invite.callId.hashCode(),
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
            (invite.callId + action).hashCode(),
            intent(context, CallActionReceiver::class.java, action, invite),
            pendingFlags(),
        )

    fun answer(context: Context, invite: IncomingInvite, onComplete: () -> Unit = {}) {
        runCatching {
            TelecomCallController(AndroidTelecomRegistrar(context)).answer(invite.callId) { success ->
                try {
                    if (success) answerFromTelecom(context, invite) else reject(context, invite)
                } finally {
                    onComplete()
                }
            }
        }.onFailure { onComplete() }
    }

    fun answerFromTelecom(context: Context, invite: IncomingInvite) {
        val call = CallServiceState.snapshot()
        if (call.callId == invite.callId && call.phase != CallPhase.Idle && call.phase != CallPhase.Ended) return
        if (claimAnswer(context, invite) == IncomingAnswerClaim.Invalid) {
            finishTerminalPresentation(context, invite.callId) {
                IncomingCallNotifier(context).cancel()
            }
            TelecomCallController(AndroidTelecomRegistrar(context)).cancel(invite.callId)
            return
        }
        CallForegroundService.start(
            context,
            intent(context, CallForegroundService::class.java, CallForegroundService.ActionAnswer, invite),
        )
    }

    fun reject(context: Context, invite: IncomingInvite) {
        TelecomCallController(AndroidTelecomRegistrar(context)).reject(invite.callId)
        rejectFromTelecom(context, invite)
    }

    fun rejectFromTelecom(context: Context, invite: IncomingInvite) {
        val alreadyTerminal = isTerminal(context, invite.callId)
        finishIncoming(context, invite)
        if (alreadyTerminal) return
        CallForegroundService.start(
            context,
            intent(context, CallForegroundService::class.java, CallForegroundService.ActionReject, invite),
        )
    }

    fun disconnectFromTelecom(context: Context, invite: IncomingInvite) {
        val alreadyTerminal = isTerminal(context, invite.callId)
        val call = CallServiceState.snapshot()
        val liveSameCall = call.callId == invite.callId &&
            call.phase != CallPhase.Idle && call.phase != CallPhase.Ended
        finishIncoming(context, invite)
        if (alreadyTerminal && !liveSameCall) return
        CallForegroundService.start(
            context,
            intent(context, CallForegroundService::class.java, CallForegroundService.ActionDisconnect, invite),
        )
    }

    private fun finishIncoming(context: Context, invite: IncomingInvite) {
        finishTerminalPresentation(context, invite.callId) {
            IncomingCallNotifier(context).cancel()
        }
    }

    companion object {
        const val ActionIncoming = "org.tinitalk.action.INCOMING_CALL"
        const val ActionAnswer = "org.tinitalk.action.ANSWER_CALL"
        const val ActionReject = "org.tinitalk.action.REJECT_CALL"

        private const val Store = "incoming_call"
        private const val TerminalStore = "terminal_calls"
        private const val TerminalEntries = "entries"
        private const val ExtraCallId = "call_id"
        private const val ExtraCaller = "caller"
        private const val ExtraCallerLogin = "caller_login"
        private const val ExtraExpiresAt = "expires_at"
        private const val ExtraLastSeq = "last_seq"
        private const val ExtraAction = "action"
        private val PendingActionLock = Any()

        fun inviteFrom(intent: Intent?): IncomingInvite? {
            val callId = intent?.getStringExtra(ExtraCallId) ?: return null
            val expiresAt = intent.getStringExtra(ExtraExpiresAt)?.let { runCatching { Instant.parse(it) }.getOrNull() }
                ?: return null
            return IncomingInvite(
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
                .putExtra(ExtraCallId, invite.callId)
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

    fun remember(stored: Set<String>, callId: String, nowMillis: Long): Set<String> {
        val entries = decode(stored, nowMillis).associateByTo(mutableMapOf()) { it.callId }
        entries[callId] = Entry(callId, nowMillis + TtlMillis)
        return encode(entries.values.sortedByDescending { it.expiresAtMillis }.take(Limit))
    }

    fun contains(stored: Set<String>, callId: String, nowMillis: Long): Boolean =
        decode(stored, nowMillis).any { it.callId == callId }

    fun prune(stored: Set<String>, nowMillis: Long): Set<String> = encode(decode(stored, nowMillis))

    private fun decode(stored: Set<String>, nowMillis: Long): List<Entry> = stored.mapNotNull { value ->
        val expiresAt = value.substringBefore(Separator).toLongOrNull() ?: return@mapNotNull null
        val callId = value.substringAfter(Separator, "")
        if (callId.isEmpty() || expiresAt <= nowMillis) null else Entry(callId, expiresAt)
    }

    private fun encode(entries: Collection<Entry>): Set<String> =
        entries.mapTo(linkedSetOf()) { "${it.expiresAtMillis}$Separator${it.callId}" }

    private data class Entry(val callId: String, val expiresAtMillis: Long)
}

data class PendingIncomingCall(val invite: IncomingInvite, val action: String?)

internal enum class IncomingAnswerClaim {
    Claimed,
    AlreadyClaimed,
    Invalid,
}
