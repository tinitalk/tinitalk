package org.tinitalk.push

import android.app.NotificationManager
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import org.tinitalk.call.CallAudioState
import org.tinitalk.call.CallPhase
import org.tinitalk.call.CallServiceState
import org.tinitalk.data.AndroidKeystoreTokenCipher
import org.tinitalk.data.AuthStore
import org.tinitalk.data.CallHistoryEvents
import org.tinitalk.data.CallUnreadState
import org.tinitalk.data.ContactRepository
import org.tinitalk.data.SharedPreferencesKeyValueStore
import org.tinitalk.telecom.AndroidTelecomRegistrar
import org.tinitalk.telecom.IncomingCallController
import org.tinitalk.telecom.TelecomCallController
import org.tinitalk.telecom.TelecomCallCallbacks
import org.tinitalk.telecom.CallForegroundService
import java.time.Instant

@Suppress("OVERRIDE_DEPRECATION")
class TinitalkMessagingService : FirebaseMessagingService() {
    @Suppress("DEPRECATION")
    override fun onNewToken(token: String) {
        val session = AuthStore(SharedPreferencesKeyValueStore(this), AndroidKeystoreTokenCipher()).load() ?: return
        DeviceRegistrar.forSession(this, session).register(DeviceRegistrar.deviceId(this), token)
    }

    @Synchronized
    override fun onMessageReceived(message: RemoteMessage) {
        val authStore = AuthStore(SharedPreferencesKeyValueStore(this), AndroidKeystoreTokenCipher())
        val session = authStore.load()
        val deviceId = DeviceRegistrar.deviceId(this)
        val replacement = IncomingPushPayload.sessionReplacement(message.data)
        if (replacement != null) {
            if (session != null && replacement.matches(session, deviceId)) {
                authStore.invalidateIfCurrent(session)
            }
            return
        }
        if (!IncomingPushPayload.matchesTarget(message.data, session, deviceId)) return

        val notifier = IncomingCallNotifier(this)
        val cancellation = IncomingPushPayload.cancellation(message.data)
        if (cancellation != null) {
            val incoming = IncomingCallController()
            incoming.rememberTerminal(this, cancellation.callId)
            val pending = incoming.load(this)?.invite
            val snapshot = CallServiceState.snapshot()
            val now = Instant.now()
            val latest = cancellation.missedFallback(pending, snapshot, now)
            if (cancellation.shouldRouteRemoteEnd(pending?.callId, snapshot)) {
                runCatching { CallForegroundService.remoteEnded(this, cancellation.callId) }
            }
            if (pending != null && !pending.expiresAt.isAfter(now)) {
                incoming.clear(this, pending.callId)
            }
            if (cancellation.shouldDismiss(pending?.callId, snapshot)) {
                TelecomCallController(AndroidTelecomRegistrar(this)).cancel(cancellation.callId)
                incoming.finishTerminalPresentation(this, cancellation.callId, notifier::cancel)
            }
            if (cancellation.shouldRefreshMissedCount()) refreshMissedCount(notifier, latest)
            return
        }
        val invite = IncomingPushPayload.parse(message.data) ?: return
        val incoming = IncomingCallController()
        val registered = incoming.presentIncoming(this, invite) {
            TelecomCallController(AndroidTelecomRegistrar(this)).addIncoming(invite, TelecomCallCallbacks(
                onAnswer = { incoming.answerFromTelecom(this, invite) },
                onDisconnect = { incoming.disconnectFromTelecom(this, invite) },
                onActive = {
                    val call = CallServiceState.snapshot()
                    val liveSameCall = call.callId == invite.callId &&
                        call.phase != CallPhase.Idle && call.phase != CallPhase.Ended
                    if (liveSameCall || !incoming.isTerminal(this, invite.callId)) {
                        CallForegroundService.telecomActive(this, invite.callId)
                    }
                },
                onInactive = {
                    val call = CallServiceState.snapshot()
                    val liveSameCall = call.callId == invite.callId &&
                        call.phase != CallPhase.Idle && call.phase != CallPhase.Ended
                    if (liveSameCall || !incoming.isTerminal(this, invite.callId)) {
                        CallForegroundService.telecomInactive(this, invite.callId)
                    }
                },
                onEndpointsChanged = { state -> CallAudioState.publish(invite.callId, state) },
            ))
        }
        if (!registered) return
        if (!IncomingCallForegroundService.show(this, invite)) {
            val mode = currentIncomingCallPresentation(this)
            val shown = notifier.presentIncoming(invite, mode) { notification ->
                getSystemService(NotificationManager::class.java)
                    .notify(IncomingCallNotifier.NotificationId, notification)
            }
            if (shown) {
                IncomingRingingAcknowledger(this).acknowledge(invite)
                if (mode == IncomingCallPresentationMode.InApp) incoming.openScreen(this, invite)
            }
        }
    }

    private fun refreshMissedCount(notifier: IncomingCallNotifier, latest: IncomingInvite?) {
        val refreshId = notifier.beginMissedCountRefresh()
        val store = AuthStore(SharedPreferencesKeyValueStore(this), AndroidKeystoreTokenCipher())
        val page = runCatching { ContactRepository(store).loadCallHistory(limit = 1) }.getOrNull()
        if (page != null) {
            val unread = CallUnreadState(page.unreadMissedCount, page.unreadMissed)
            val update = notifier.updateMissedCount(page.unreadMissedCount, refreshId, latest)
            if (update.applied) CallHistoryEvents.publish(unread)
        } else {
            latest?.let(notifier::showMissedIfAbsent)
        }
    }
}
