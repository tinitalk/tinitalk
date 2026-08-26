package org.tinitalk.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import org.tinitalk.call.CallAudioState
import org.tinitalk.call.CallServiceState
import org.tinitalk.data.AndroidKeystoreTokenCipher
import org.tinitalk.data.AuthStore
import org.tinitalk.data.SharedPreferencesKeyValueStore
import org.tinitalk.telecom.AndroidTelecomRegistrar
import org.tinitalk.telecom.IncomingCallController
import org.tinitalk.telecom.TelecomCallController
import org.tinitalk.telecom.TelecomCallCallbacks
import org.tinitalk.telecom.CallForegroundService

@Suppress("OVERRIDE_DEPRECATION")
class TinitalkMessagingService : FirebaseMessagingService() {
    @Suppress("DEPRECATION")
    override fun onNewToken(token: String) {
        val session = AuthStore(SharedPreferencesKeyValueStore(this), AndroidKeystoreTokenCipher()).load() ?: return
        DeviceRegistrar.forSession(this, session).register(DeviceRegistrar.deviceId(this), token)
    }

    @Synchronized
    override fun onMessageReceived(message: RemoteMessage) {
        val notifier = IncomingCallNotifier(this)
        val cancellation = IncomingPushPayload.cancellation(message.data)
        if (cancellation != null) {
            val incoming = IncomingCallController()
            incoming.rememberTerminal(this, cancellation.callId)
            val pending = incoming.load(this)?.invite
            val snapshot = CallServiceState.snapshot()
            if (cancellation.shouldDismiss(pending?.callId, snapshot)) {
                if (pending != null && cancellation.shouldShowMissed(pending.callId, snapshot)) {
                    notifier.showMissed(pending)
                }
                TelecomCallController(AndroidTelecomRegistrar(this)).cancel(cancellation.callId)
                notifier.cancel()
                incoming.clear(this, cancellation.callId)
            }
            return
        }
        val invite = IncomingPushPayload.parse(message.data) ?: return
        val incoming = IncomingCallController()
        if (incoming.isTerminal(this, invite.callId)) return
        incoming.save(this, invite)
        TelecomCallController(AndroidTelecomRegistrar(this)).addIncoming(invite, TelecomCallCallbacks(
            onAnswer = { incoming.answerFromTelecom(this, invite) },
            onDisconnect = { incoming.disconnectFromTelecom(this, invite) },
            onActive = { CallForegroundService.telecomActive(this, invite.callId) },
            onInactive = { CallForegroundService.telecomInactive(this, invite.callId) },
            onEndpointsChanged = { state -> CallAudioState.publish(invite.callId, state) },
        ))
        if (!IncomingCallForegroundService.show(this, invite)) {
            notifier.show(invite)
            incoming.openScreen(this, invite)
        }
    }
}
