package org.tinitalk.telecom

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val invite = IncomingCallController.inviteFrom(intent) ?: return
        val controller = IncomingCallController()
        when (intent.action) {
            IncomingCallController.ActionAnswer -> controller.answer(context, invite)
            IncomingCallController.ActionReject -> controller.reject(context, invite)
        }
    }
}
