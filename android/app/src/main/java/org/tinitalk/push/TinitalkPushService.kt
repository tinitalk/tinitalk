package org.tinitalk.push

import android.app.NotificationManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.tinitalk.missedCalls
import org.tinitalk.call.AccountCallOwner
import org.tinitalk.call.AccountCallKey
import org.tinitalk.call.CallAudioState
import org.tinitalk.call.CallPhase
import org.tinitalk.call.CallServiceState
import org.tinitalk.call.CallSessionBinding
import org.tinitalk.call.GlobalCallAdmission
import org.tinitalk.cleanupWebPushAccount
import org.tinitalk.data.AccountId
import org.tinitalk.data.AccountRecord
import org.tinitalk.data.AndroidKeystoreTokenCipher
import org.tinitalk.data.AuthStore
import org.tinitalk.data.SharedPreferencesKeyValueStore
import org.tinitalk.telecom.AndroidTelecomRegistrar
import org.tinitalk.telecom.CallForegroundService
import org.tinitalk.telecom.IncomingAdmissionResult
import org.tinitalk.telecom.IncomingCallController
import org.tinitalk.telecom.TelecomCallCallbacks
import org.tinitalk.telecom.TelecomCallController
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage
import java.time.Instant

class TinitalkPushService : PushService() {
    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        val accountId = instance.toAccountId() ?: return
        val keys = endpoint.pubKeySet ?: return
        val subscription = WebPushSubscription(
            endpoint = endpoint.url,
            keys = WebPushKeys(keys.pubKey, keys.auth),
        ).takeIf(WebPushSubscription::isValid) ?: return
        if (GlobalWebPushEndpointHandoff.complete(accountId, subscription)) return
        PushRegistrationScheduler(this).enqueueUrgent(accountId, subscription)
    }

    @Synchronized
    override fun onMessage(message: PushMessage, instance: String) {
        if (!message.decrypted) return
        val accountId = instance.toAccountId() ?: return
        val account = authStore().get(accountId) ?: return
        val data = runCatching {
            Gson().fromJson<Map<String, String>>(
                message.content.toString(Charsets.UTF_8),
                object : TypeToken<Map<String, String>>() {}.type,
            )
        }.getOrNull() ?: return
        IncomingPushHandler(this).handle(account, data)
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        val accountId = instance.toAccountId() ?: return
        if (GlobalWebPushEndpointHandoff.fail(
                accountId,
                IllegalStateException("WebPush registration failed: $reason"),
            )
        ) return
        PushRegistrationScheduler(this).enqueueUrgent(accountId)
    }

    override fun onUnregistered(instance: String) {
        val accountId = instance.toAccountId() ?: return
        if (GlobalWebPushEndpointHandoff.fail(
                accountId,
                IllegalStateException("WebPush registration was removed"),
            )
        ) return
        PushRegistrationScheduler(this).enqueueUrgent(accountId)
    }

    private fun authStore() = AuthStore(SharedPreferencesKeyValueStore(this), AndroidKeystoreTokenCipher())
}

internal class IncomingPushHandler(
    private val context: android.content.Context,
    private val disconnectSystemCall: (AccountCallKey) -> Unit = {
        TelecomCallController(AndroidTelecomRegistrar(context)).cancel(it)
    },
) {
    fun handle(account: AccountRecord, data: Map<String, String>) {
        val session = account.session
        val deviceId = DeviceIdentity.id(context)
        val replacement = IncomingPushPayload.sessionReplacement(data)
        if (replacement != null) {
            if (!replacement.matches(session, deviceId)) return
            invalidateReplacedAccount(account, authStore()) { accountId ->
                cleanupWebPushAccount(context, accountId)
            }
            return
        }
        if (!IncomingPushPayload.matchesTarget(data, session, deviceId)) return

        if (data["type"] == "contact_changed") {
            val login = data["contact_login"]?.takeIf(String::isNotBlank) ?: return
            ContactRefreshScheduler(context).enqueue(account, login)
            return
        }

        val handler = IncomingCallHandler(context, disconnectSystemCall)
        val cancellation = IncomingPushPayload.cancellation(data, account.id)
        if (cancellation != null) {
            handler.cancel(account, cancellation)
            return
        }
        val invite = IncomingPushPayload.parse(data, account) ?: return
        handler.present(invite)
    }

    private fun authStore() = AuthStore(SharedPreferencesKeyValueStore(context), AndroidKeystoreTokenCipher())
}

/** Shared admission and presentation for authenticated socket events and push payloads. */
internal class IncomingCallHandler(
    private val context: android.content.Context,
    private val disconnectSystemCall: (AccountCallKey) -> Unit = {
        TelecomCallController(AndroidTelecomRegistrar(context)).cancel(it)
    },
) {
    fun present(invite: IncomingInvite) {
        if (org.tinitalk.call.WaitingCalls.present(invite)) return
        val notifier = IncomingCallNotifier(context)
        val incoming = IncomingCallController()
        if (incoming.load(context)?.invite?.let { it.owner == invite.owner && it.serverAccepted } == true) return
        when (incoming.admitIncoming(context, invite)) {
            IncomingAdmissionResult.Invalid -> return
            IncomingAdmissionResult.Busy -> {
                IncomingRingingAcknowledger(context).rejectBusy(invite)
                return
            }
            IncomingAdmissionResult.Duplicate -> return
            IncomingAdmissionResult.Admitted -> Unit
        }
        val stillOwned = incoming.presentSavedIncoming(context, invite) {
            runCatching {
                TelecomCallController(AndroidTelecomRegistrar(context)).addIncoming(invite, TelecomCallCallbacks(
                    onAnswer = { incoming.answerFromTelecom(context, invite) },
                    onDisconnect = { incoming.disconnectFromTelecom(context, invite) },
                    onActive = {
                        val call = CallServiceState.snapshot()
                        val liveSameCall = call.callKey == invite.key &&
                            call.phase != CallPhase.Idle && call.phase != CallPhase.Ended
                        if (GlobalCallAdmission.current()?.owner == invite.owner &&
                            (liveSameCall || !incoming.isTerminal(context, invite.owner))
                        ) {
                            CallForegroundService.telecomActive(context, invite.key)
                        }
                    },
                    onInactive = {
                        val call = CallServiceState.snapshot()
                        val liveSameCall = call.callKey == invite.key &&
                            call.phase != CallPhase.Idle && call.phase != CallPhase.Ended
                        if (GlobalCallAdmission.current()?.owner == invite.owner &&
                            (liveSameCall || !incoming.isTerminal(context, invite.owner))
                        ) {
                            CallForegroundService.telecomInactive(context, invite.key)
                        }
                    },
                    onEndpointsChanged = { state ->
                        if (GlobalCallAdmission.current()?.owner == invite.owner) {
                            CallAudioState.publish(invite.key, state)
                        }
                    },
                ))
            }
        }
        if (!stillOwned) return
        if (invite.serverAccepted) {
            incoming.answer(context, invite)
            incoming.openScreen(context, invite)
            return
        }
        if (!IncomingCallForegroundService.show(context, invite)) {
            val mode = currentIncomingCallPresentation(context)
            val shown = notifier.presentIncoming(invite, mode) { notification ->
                context.getSystemService(NotificationManager::class.java)
                    .notify(IncomingCallNotifier.NotificationId, notification)
            }
            if (shown) {
                IncomingRingingAcknowledger(context).acknowledge(invite)
                if (mode == IncomingCallPresentationMode.InApp) incoming.openScreen(context, invite)
                scheduleIncomingExpiry(context, invite)
            } else {
                val finished = incoming.finishTerminalPresentation(context, invite.owner, notifier::cancel)
                if (finished) {
                    runCatching { TelecomCallController(AndroidTelecomRegistrar(context)).cancel(invite.key) }
                    IncomingRingingAcknowledger(context).rejectBusy(invite)
                }
            }
        }
    }

    fun cancel(
        account: AccountRecord,
        cancellation: CallCancellation,
    ) {
        val notifier = IncomingCallNotifier(context)
        val incoming = IncomingCallController()
        val owner = AccountCallOwner(cancellation.key, CallSessionBinding.from(account.session))
        // call.accept cancels the ringing notification, not the selected handoff.
        if (cancellation.eventType == "call.accept" && (
                org.tinitalk.call.WaitingCalls.isAnswering(owner) ||
                incoming.load(context)?.invite?.let { it.owner == owner && it.serverAccepted } == true
            )) return
        org.tinitalk.call.WaitingCalls.cancel(owner)
        if (!incoming.rememberTerminalIfCompatible(context, owner)) return
        val pending = incoming.load(context)?.invite
        val snapshot = CallServiceState.snapshot()
        val now = Instant.now()
        val latest = cancellation.missedFallback(pending, snapshot, now)
        val remoteEndQueued = cancellation.shouldRouteRemoteEnd(pending?.key, snapshot) &&
            runCatching { CallForegroundService.remoteEnded(context, owner) }.isSuccess
        if (cancellation.shouldDismiss(pending?.key, snapshot)) {
            if (remoteEndQueued) {
                // The call service owns audio until its terminal tone finishes.
                // A simultaneous push must not disconnect Telecom underneath it.
                incoming.handoffTerminalPresentation(context, owner, notifier::cancel)
            } else {
                disconnectSystemCall(cancellation.key)
                incoming.finishTerminalPresentation(context, owner, notifier::cancel)
            }
        }
        if (pending != null && !pending.expiresAt.isAfter(now)) incoming.pruneExpiredPending(context, now)
        if (cancellation.shouldRefreshMissedCount()) scheduleMissedCountRefresh(latest, account)
    }

    private fun scheduleMissedCountRefresh(
        latest: IncomingInvite?,
        account: AccountRecord,
    ) {
        val store = authStore()
        val missedCalls = missedCalls(context)
        missedCalls.syncAccounts(store.list().map { it.id })
        latest?.let { missedCalls.recordMissedIfAbsent(account.id, it.toMissedCall()) }
        MissedCountRefreshScheduler(context).enqueue(account.id)
    }

    private fun authStore() = AuthStore(SharedPreferencesKeyValueStore(context), AndroidKeystoreTokenCipher())
}

internal fun invalidateReplacedAccount(
    account: AccountRecord,
    authStore: AuthStore,
    onAccountRemoved: (AccountId) -> Unit,
): Boolean {
    val removed = authStore.invalidateIfCurrent(account.id, account.session)
    if (removed) onAccountRemoved(account.id)
    return removed
}

private fun String.toAccountId(): AccountId? = runCatching { AccountId(this) }.getOrNull()
