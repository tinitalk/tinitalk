package org.tinitalk.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import org.tinitalk.data.AndroidKeystoreTokenCipher
import org.tinitalk.data.AuthStore
import org.tinitalk.data.SharedPreferencesKeyValueStore
import org.tinitalk.telecom.IncomingCallController
import org.tinitalk.telecom.AndroidTelecomRegistrar
import org.tinitalk.telecom.TelecomCallController

@Suppress("OVERRIDE_DEPRECATION")
class TinitalkMessagingService : FirebaseMessagingService() {
    @Suppress("DEPRECATION")
    override fun onNewToken(token: String) {
        val session = AuthStore(SharedPreferencesKeyValueStore(this), AndroidKeystoreTokenCipher()).load() ?: return
        DeviceRegistrar.forSession(this, session).register(DeviceRegistrar.deviceId(this))
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val notifier = IncomingCallNotifier(this)
        if (IncomingPushPayload.action(message.data) == PushAction.Cancel) {
            message.data["call_id"]?.let {
                TelecomCallController(AndroidTelecomRegistrar(this)).cancel(it)
            }
            notifier.cancel()
            IncomingCallController().clear(this)
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
