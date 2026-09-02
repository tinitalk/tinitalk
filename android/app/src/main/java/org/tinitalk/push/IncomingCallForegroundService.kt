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
import org.tinitalk.call.AccountCallOwner
import org.tinitalk.telecom.IncomingCallController
import org.tinitalk.telecom.AndroidTelecomRegistrar
import org.tinitalk.telecom.TelecomCallController
import java.time.Instant

class IncomingCallForegroundService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val ringingAcknowledger by lazy { IncomingRingingAcknowledger(this) }
    private var stopTask: Runnable? = null
    private var foreground = false
    private var presentedOwner: AccountCallOwner? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val incoming = IncomingCallController()
        val invite = IncomingCallController.inviteFrom(intent)
        if (invite == null) {
            if (presentedOwner == null) stopPresentation(startId)
            return START_NOT_STICKY
        }
        if (!invite.expiresAt.isAfter(Instant.now())) {
            expire(invite, startId)
            return START_NOT_STICKY
        }

        val notifier = IncomingCallNotifier(this)
        val mode = currentIncomingCallPresentation(this)
        var foregroundStarted = false
        var fallbackPresented = false
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
                            presentedOwner = invite.owner
                        },
                        acknowledgeRinging = ringingAcknowledger::acknowledge,
                        openFullScreen = { incoming.openScreen(this, it) },
                    ).present(invite, mode)
                }.isSuccess
                if (!foregroundStarted) {
                    fallbackPresented = runCatching {
                        IncomingCallForegroundPresentation(
                            enterForeground = {
                                getSystemService(NotificationManager::class.java)
                                    .notify(IncomingCallNotifier.NotificationId, notification)
                            },
                            acknowledgeRinging = ringingAcknowledger::acknowledge,
                            openFullScreen = { incoming.openScreen(this, it) },
                        ).present(invite, mode)
                    }.isSuccess
                }
            }
        }.getOrDefault(false)

        when {
            presented && foregroundStarted -> scheduleStop(invite, startId)
            presented && fallbackPresented -> {
                scheduleIncomingExpiry(this, invite)
                stopPresentation(startId, invite.owner)
            }
            else -> {
                val finished = incoming.finishTerminalPresentation(this, invite.owner, notifier::cancel)
                if (finished) {
                    runCatching { TelecomCallController(AndroidTelecomRegistrar(this)).cancel(invite.key) }
                    ringingAcknowledger.rejectBusy(invite)
                }
                stopPresentation(startId, invite.owner)
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopTask?.let(handler::removeCallbacks)
        stopTask = null
        ringingAcknowledger.close()
        detachForeground(removeNotification = shouldRemoveForegroundNotification())
        presentedOwner = null
        super.onDestroy()
    }

    private fun scheduleStop(invite: IncomingInvite, startId: Int) {
        stopTask?.let(handler::removeCallbacks)
        val task = Runnable { expire(invite, startId) }
        stopTask = task
        val remainingMillis = java.time.Duration.between(Instant.now(), invite.expiresAt)
            .toMillis()
            .coerceAtLeast(1)
        handler.postDelayed(task, remainingMillis)
    }

    private fun expire(invite: IncomingInvite, startId: Int) {
        val incoming = IncomingCallController()
        val now = Instant.now()
        val expired = incoming.expirePending(this, invite.owner, now) {
            IncomingCallNotifier(this).cancel()
            runCatching { TelecomCallController(AndroidTelecomRegistrar(this)).cancel(invite.key) }
        }
        if (!expired && invite.expiresAt.isAfter(now) && incoming.ownsIncoming(this, invite, now)) {
            scheduleStop(invite, startId)
            return
        }
        stopPresentation(startId, invite.owner)
    }

    private fun stopPresentation(startId: Int, expectedOwner: AccountCallOwner? = null) {
        if (expectedOwner != null && presentedOwner != null && presentedOwner != expectedOwner) return
        val stopsCurrentForeground = expectedOwner != null && presentedOwner == expectedOwner
        val removeNotification = shouldRemoveForegroundNotification()
        if (presentedOwner == expectedOwner) presentedOwner = null
        detachForeground(removeNotification)
        if (stopsCurrentForeground) stopSelf() else stopSelfResult(startId)
    }

    private fun shouldRemoveForegroundNotification(): Boolean {
        val owner = presentedOwner
        val incoming = IncomingCallController()
        val pendingInvite = incoming.load(this)?.invite
        val terminal = owner?.let { incoming.isTerminal(this, it) } == true
        return shouldRemoveIncomingForegroundNotification(owner, pendingInvite, terminal, Instant.now())
    }

    private fun detachForeground(removeNotification: Boolean) {
        if (foreground) {
            stopForeground(if (removeNotification) STOP_FOREGROUND_REMOVE else STOP_FOREGROUND_DETACH)
        }
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

internal fun shouldRemoveIncomingForegroundNotification(
    presentedOwner: AccountCallOwner?,
    pendingInvite: IncomingInvite?,
    terminal: Boolean,
    now: Instant = Instant.now(),
): Boolean {
    if (presentedOwner == null || terminal) return true
    if (pendingInvite?.owner != presentedOwner) return true
    return !pendingInvite.expiresAt.isAfter(now)
}

internal fun scheduleIncomingExpiry(context: Context, invite: IncomingInvite) {
    val appContext = context.applicationContext
    Handler(Looper.getMainLooper()).postDelayed(
        {
            val incoming = IncomingCallController()
            val now = Instant.now()
            val expired = incoming.expirePending(appContext, invite.owner, now) {
                IncomingCallNotifier(appContext).cancel()
                runCatching { TelecomCallController(AndroidTelecomRegistrar(appContext)).cancel(invite.key) }
            }
            if (!expired && invite.expiresAt.isAfter(now) && incoming.ownsIncoming(appContext, invite, now)) {
                scheduleIncomingExpiry(appContext, invite)
            }
        },
        java.time.Duration.between(Instant.now(), invite.expiresAt).toMillis().coerceAtLeast(1),
    )
}
