package org.tinitalk.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import org.tinitalk.call.CallServiceState
import org.tinitalk.data.AndroidKeystoreTokenCipher
import org.tinitalk.data.AuthStore
import org.tinitalk.data.SharedPreferencesKeyValueStore
import org.tinitalk.telecom.AndroidTelecomRegistrar
import org.tinitalk.telecom.IncomingCallController
import org.tinitalk.telecom.TelecomCallController

@Suppress("OVERRIDE_DEPRECATION")
class TinitalkMessagingService : FirebaseMessagingService() {
    @Suppress("DEPRECATION")
    override fun onNewToken(token: String) {
        val session = AuthStore(SharedPreferencesKeyValueStore(this), AndroidKeystoreTokenCipher()).load() ?: return
        DeviceRegistrar.forSession(this, session).register(DeviceRegistrar.deviceId(this), token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val notifier = IncomingCallNotifier(this)
        val cancellation = IncomingPushPayload.cancellation(message.data)
        if (cancellation != null) {
            val incoming = IncomingCallController()
            if (cancellation.shouldDismiss(incoming.load(this)?.invite?.callId, CallServiceState.snapshot())) {
                TelecomCallController(AndroidTelecomRegistrar(this)).cancel(cancellation.callId)
                notifier.cancel()
                incoming.clear(this, cancellation.callId)
            }
            return
        }
        val invite = IncomingPushPayload.parse(message.data) ?: return
        val incoming = IncomingCallController()
        TelecomCallController(AndroidTelecomRegistrar(this)).addIncoming(
            invite,
            onAnswer = { incoming.answerFromTelecom(this, invite) },
            onDisconnect = { incoming.disconnectFromTelecom(this, invite) },
        )
        notifier.show(invite)
    }
}
