package org.tinitalk.push

import android.app.NotificationManager
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
        if (invite == null || !invite.expiresAt.isAfter(Instant.now())) {
            stopPresentation(startId)
            return START_NOT_STICKY
        }

        val notifier = IncomingCallNotifier(this)
        val mode = currentIncomingCallPresentation(this)
        var foregroundStarted = false
        val presented = runCatching {
            notifier.presentIncoming(invite, mode) { notification ->
                foregroundStarted = runCatching {
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
                    ).present(invite, mode)
                }.isSuccess
                if (!foregroundStarted) {
                    IncomingCallForegroundPresentation(
                        enterForeground = {
                            getSystemService(NotificationManager::class.java)
                                .notify(IncomingCallNotifier.NotificationId, notification)
                        },
                        acknowledgeRinging = ringingAcknowledger::acknowledge,
                        openFullScreen = { incoming.openScreen(this, it) },
                    ).present(invite, mode)
                }
            }
        }.getOrDefault(false)

        if (!presented || !foregroundStarted) {
            stopPresentation(startId)
        } else {
            scheduleStop(invite, startId)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopTask?.let(handler::removeCallbacks)
        stopTask = null
        detachForeground()
        super.onDestroy()
    }

    private fun scheduleStop(invite: IncomingInvite, startId: Int) {
        stopTask?.let(handler::removeCallbacks)
        val task = Runnable { stopPresentation(startId) }
        stopTask = task
        val remainingMillis = java.time.Duration.between(Instant.now(), invite.expiresAt)
            .toMillis()
            .coerceAtLeast(0)
        handler.postDelayed(task, remainingMillis)
    }

    private fun stopPresentation(startId: Int) {
        detachForeground()
        stopSelfResult(startId)
    }

    private fun detachForeground() {
        // Terminal paths cancel the scoped notification themselves. Detaching first also keeps a
        // newer fallback notification with the shared ID alive while this service is stopping.
        if (foreground) stopForeground(STOP_FOREGROUND_DETACH)
        foreground = false
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
