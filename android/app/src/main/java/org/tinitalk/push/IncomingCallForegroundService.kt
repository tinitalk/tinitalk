package org.tinitalk.push

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.tinitalk.telecom.IncomingCallController
import java.time.Instant

class IncomingCallForegroundService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val ringingAcknowledger by lazy { IncomingRingingAcknowledger(this) }
    private var stopTask: Runnable? = null
    private var foreground = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val incoming = IncomingCallController()
        val invite = IncomingCallController.inviteFrom(intent)
        if (invite == null || !invite.expiresAt.isAfter(Instant.now()) || incoming.isTerminal(this, invite.callId)) {
            stopSelf()
            return START_NOT_STICKY
        }

        val notifier = IncomingCallNotifier(this)
        val notification = notifier.buildIncomingNotification(invite)
        if (notification == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val foregroundStarted = runCatching {
            IncomingCallForegroundPresentation(
                enterForeground = {
                    ServiceCompat.startForeground(
                        this,
                        IncomingCallNotifier.NotificationId,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL,
                    )
                    foreground = true
                },
                acknowledgeRinging = ringingAcknowledger::acknowledge,
                openFullScreen = { incoming.openScreen(this, it) },
            ).present(invite)
        }.isSuccess

        if (!foregroundStarted) {
            IncomingCallForegroundPresentation(
                enterForeground = notifier::show,
                acknowledgeRinging = ringingAcknowledger::acknowledge,
                openFullScreen = { incoming.openScreen(this, it) },
            ).present(invite)
            stopSelf()
        } else {
            scheduleStop(invite)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopTask?.let(handler::removeCallbacks)
        stopTask = null
        if (foreground) stopForeground(STOP_FOREGROUND_REMOVE)
        foreground = false
        super.onDestroy()
    }

    private fun scheduleStop(invite: IncomingInvite) {
        stopTask?.let(handler::removeCallbacks)
        val task = Runnable { stopSelf() }
        stopTask = task
        val remainingMillis = java.time.Duration.between(Instant.now(), invite.expiresAt)
            .toMillis()
            .coerceAtLeast(0)
        handler.postDelayed(task, remainingMillis)
    }

    companion object {
        internal const val ActionShow = "org.tinitalk.action.SHOW_INCOMING_CALL"

        fun show(context: Context, invite: IncomingInvite): Boolean = runCatching {
            ContextCompat.startForegroundService(
                context,
                IncomingCallController().presentationIntent(context, invite),
            )
        }.isSuccess

        fun stop(context: Context) {
            context.stopService(Intent(context, IncomingCallForegroundService::class.java))
        }
    }
}
