package org.tinitalk.push

import android.app.NotificationManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.tinitalk.call.AccountCallOwner
import org.tinitalk.call.CallAudioState
import org.tinitalk.call.CallPhase
import org.tinitalk.call.CallServiceState
import org.tinitalk.call.CallSessionBinding
import org.tinitalk.call.GlobalCallAdmission
import org.tinitalk.cleanupWebPushAccount
import org.tinitalk.data.AccountId
import org.tinitalk.data.AccountRecord
import org.tinitalk.data.AccountUnreadState
import org.tinitalk.data.AndroidKeystoreTokenCipher
import org.tinitalk.data.AuthStore
import org.tinitalk.data.CallHistoryEvents
import org.tinitalk.data.ContactRepository
import org.tinitalk.data.Session
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

        val authStore = authStore()
        val account = authStore.get(accountId) ?: return
        val config = authStore.webPushConfig(accountId) ?: return
        runCatching {
            persistRegisteredSubscription(
                accountId = accountId,
                subscription = subscription,
                config = config,
                session = account.session,
                deviceId = DeviceIdentity.id(this),
                store = PushRegistrationStore(this),
                enqueue = { PushRegistrationScheduler(this).enqueue(accountId) },
            )
        }
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
        GlobalWebPushEndpointHandoff.fail(accountId, IllegalStateException("WebPush registration failed: $reason"))
        if (authStore().get(accountId) != null) PushRegistrationScheduler(this).enqueue(accountId)
    }

    override fun onUnregistered(instance: String) {
        val accountId = instance.toAccountId() ?: return
        GlobalWebPushEndpointHandoff.fail(accountId, IllegalStateException("WebPush registration was removed"))
        if (authStore().get(accountId) != null) PushRegistrationScheduler(this).enqueue(accountId)
    }

    private fun authStore() = AuthStore(SharedPreferencesKeyValueStore(this), AndroidKeystoreTokenCipher())
}

internal class IncomingPushHandler(private val context: android.content.Context) {
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

        val notifier = IncomingCallNotifier(context)
        val cancellation = IncomingPushPayload.cancellation(data, account.id)
        if (cancellation != null) {
            handleCancellation(account, cancellation, notifier)
            return
        }
        val invite = IncomingPushPayload.parse(data, account) ?: return
        val incoming = IncomingCallController()
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

    private fun handleCancellation(
        account: AccountRecord,
        cancellation: CallCancellation,
        notifier: IncomingCallNotifier,
    ) {
        val incoming = IncomingCallController()
        val owner = AccountCallOwner(cancellation.key, CallSessionBinding.from(account.session))
        if (!incoming.rememberTerminalIfCompatible(context, owner)) return
        val pending = incoming.load(context)?.invite
        val snapshot = CallServiceState.snapshot()
        val now = Instant.now()
        val latest = cancellation.missedFallback(pending, snapshot, now)
        val remoteEndQueued = cancellation.shouldRouteRemoteEnd(pending?.key, snapshot) &&
            runCatching { CallForegroundService.remoteEnded(context, owner) }.isSuccess
        if (pending != null && !pending.expiresAt.isAfter(now)) incoming.pruneExpiredPending(context, now)
        if (cancellation.shouldDismiss(pending?.key, snapshot)) {
            TelecomCallController(AndroidTelecomRegistrar(context)).cancel(cancellation.key)
            if (remoteEndQueued) {
                incoming.handoffTerminalPresentation(context, owner, notifier::cancel)
            } else {
                incoming.finishTerminalPresentation(context, owner, notifier::cancel)
            }
        }
        if (cancellation.shouldRefreshMissedCount()) refreshMissedCount(notifier, latest, account.id)
    }

    private fun refreshMissedCount(notifier: IncomingCallNotifier, latest: IncomingInvite?, accountId: AccountId) {
        val store = authStore()
        val pinned = store.get(accountId) ?: return
        notifier.syncMissedAccounts(store.list().map { it.id })
        val refreshId = notifier.beginAccountMissedCountRefresh(accountId)
        val page = runCatching {
            ContactRepository(store).loadCallHistory(accountId, limit = 1, expectedSession = pinned.session)
        }.getOrNull()
        if (page != null) {
            store.withCurrent(accountId, pinned.session) {
                val update = notifier.updateAccountMissedState(
                    accountId,
                    page.unread,
                    refreshId,
                    latest,
                    redialBinding = CallSessionBinding.from(pinned.session),
                    immediate = true,
                )
                if (update.applied) {
                    CallHistoryEvents.publish(AccountUnreadState(accountId, page.unread, pinned.session))
                }
            }
        } else {
            store.withCurrent(accountId, pinned.session) {
                notifier.syncMissedAccounts(store.list().map { it.id })
                latest?.let { notifier.showAccountMissedIfAbsent(accountId, it) }
            }
        }
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

internal fun persistRegisteredSubscription(
    accountId: AccountId,
    subscription: WebPushSubscription,
    config: StoredWebPushConfig,
    session: Session,
    deviceId: String,
    store: PushRegistrationStore,
    enqueue: () -> Unit,
) {
    session.sessionId?.takeIf(String::isNotBlank) ?: return
    var failure: Exception? = null
    try {
        val current = store.loadBoundTo(accountId, config, session, deviceId)
        if (current?.subscription != subscription) {
            store.upsert(accountId, session, deviceId, subscription)
        }
    } catch (error: Exception) {
        failure = error
    }
    try {
        enqueue()
    } catch (error: Exception) {
        if (failure == null) failure = error else failure.addSuppressed(error)
    }
    failure?.let { throw it }
}

private fun String.toAccountId(): AccountId? = runCatching { AccountId(this) }.getOrNull()
