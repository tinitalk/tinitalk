package org.tinitalk.telecom

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean

class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val invite = IncomingCallController.inviteFrom(intent) ?: return
        val controller = IncomingCallController()
        val pending = PendingCallAction(goAsync()::finish)
        Handler(Looper.getMainLooper()).postDelayed(pending::finish, ActionTimeoutMillis)
        when (intent.action) {
            IncomingCallController.ActionAnswer -> controller.answer(context, invite, pending::finish)
            IncomingCallController.ActionReject -> {
                controller.reject(context, invite)
                pending.finish()
            }
            else -> pending.finish()
        }
    }

    private companion object {
        const val ActionTimeoutMillis = 5_000L
    }
}

internal class PendingCallAction(private val finishAction: () -> Unit) {
    private val finished = AtomicBoolean()

    fun finish() {
        if (finished.compareAndSet(false, true)) finishAction()
    }
}
