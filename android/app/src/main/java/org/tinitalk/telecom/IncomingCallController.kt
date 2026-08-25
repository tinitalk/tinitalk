package org.tinitalk.telecom

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import org.tinitalk.MainActivity
import org.tinitalk.push.IncomingCallNotifier
import org.tinitalk.push.IncomingInvite
import java.time.Instant

class IncomingCallController {
    fun save(context: Context, invite: IncomingInvite, action: String? = null) {
        prefs(context).edit()
            .putString(ExtraCallId, invite.callId)
            .putString(ExtraCaller, invite.caller)
            .putString(ExtraExpiresAt, invite.expiresAt.toString())
            .putLong(ExtraLastSeq, invite.lastSeq)
            .putString(ExtraAction, action)
            .apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
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
                lastSeq = prefs.getLong(ExtraLastSeq, 0),
            ),
            prefs.getString(ExtraAction, null),
        )
    }

    fun activityIntent(context: Context, action: String, invite: IncomingInvite): PendingIntent =
        PendingIntent.getActivity(
            context,
            invite.callId.hashCode(),
            intent(context, MainActivity::class.java, action, invite)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            pendingFlags(),
        )

    fun actionIntent(context: Context, action: String, invite: IncomingInvite): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            (invite.callId + action).hashCode(),
            intent(context, CallActionReceiver::class.java, action, invite),
            pendingFlags(),
        )

    fun answer(context: Context, invite: IncomingInvite) {
        save(context, invite, ActionAnswer)
        val service = Intent(context, CallForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(service)
        } else {
            context.startService(service)
        }
        context.startActivity(
            intent(context, MainActivity::class.java, ActionAnswer, invite)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
    }

    fun reject(context: Context, invite: IncomingInvite) {
        save(context, invite, ActionReject)
        IncomingCallNotifier(context).cancel()
        context.startActivity(
            intent(context, MainActivity::class.java, ActionReject, invite)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
    }

    companion object {
        const val ActionIncoming = "org.tinitalk.action.INCOMING_CALL"
        const val ActionAnswer = "org.tinitalk.action.ANSWER_CALL"
        const val ActionReject = "org.tinitalk.action.REJECT_CALL"

        private const val Store = "incoming_call"
        private const val ExtraCallId = "call_id"
        private const val ExtraCaller = "caller"
        private const val ExtraExpiresAt = "expires_at"
        private const val ExtraLastSeq = "last_seq"
        private const val ExtraAction = "action"

        fun inviteFrom(intent: Intent?): IncomingInvite? {
            val callId = intent?.getStringExtra(ExtraCallId) ?: return null
            val expiresAt = intent.getStringExtra(ExtraExpiresAt)?.let { runCatching { Instant.parse(it) }.getOrNull() }
                ?: return null
            return IncomingInvite(
                callId = callId,
                caller = intent.getStringExtra(ExtraCaller).orEmpty(),
                expiresAt = expiresAt,
                lastSeq = intent.getLongExtra(ExtraLastSeq, 0),
            )
        }

        private fun prefs(context: Context) =
            context.getSharedPreferences(Store, Context.MODE_PRIVATE)

        private fun pendingFlags() = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        private fun intent(context: Context, target: Class<*>, action: String, invite: IncomingInvite): Intent =
            Intent(context, target)
                .setAction(action)
                .putExtra(ExtraCallId, invite.callId)
                .putExtra(ExtraCaller, invite.caller)
                .putExtra(ExtraExpiresAt, invite.expiresAt.toString())
                .putExtra(ExtraLastSeq, invite.lastSeq)
    }
}

data class PendingIncomingCall(val invite: IncomingInvite, val action: String?)
