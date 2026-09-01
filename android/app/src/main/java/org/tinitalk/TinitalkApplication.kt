package org.tinitalk

import android.app.Activity
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import org.tinitalk.call.CallSessionBinding
import org.tinitalk.call.GlobalCallAdmission
import org.tinitalk.call.resolvePinnedCallSession
import org.tinitalk.data.AccountId
import org.tinitalk.data.AndroidKeystoreTokenCipher
import org.tinitalk.data.AuthSessionEvent
import org.tinitalk.data.AuthSessionEvents
import org.tinitalk.data.AuthStore
import org.tinitalk.data.ContactCache
import org.tinitalk.data.Session
import org.tinitalk.data.SharedPreferencesKeyValueStore
import org.tinitalk.network.NetworkAvailability
import org.tinitalk.push.IncomingCallForegroundService
import org.tinitalk.push.IncomingCallNotifier
import org.tinitalk.push.IncomingCallPresentationMode
import org.tinitalk.push.IncomingRingingAcknowledger
import org.tinitalk.push.PushRegistrationScheduler
import org.tinitalk.push.UnifiedPushAccountRegistration
import org.tinitalk.push.currentIncomingCallPresentation
import org.tinitalk.push.scheduleIncomingExpiry
import org.tinitalk.telecom.AndroidTelecomRegistrar
import org.tinitalk.telecom.CallForegroundService
import org.tinitalk.telecom.IncomingCallController
import org.tinitalk.telecom.TelecomCallController

class TinitalkApplication : Application() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var authStore: AuthStore
    lateinit var networkAvailability: NetworkAvailability
        private set
    private val authSessionObserver: (AuthSessionEvent) -> Unit = {
        mainHandler.post {
            it.accountId?.let { accountId ->
                IncomingCallNotifier(this).syncMissedAccounts(authStore.list().map { record -> record.id })
                cleanupWebPushAccount(this, accountId)
            }
            stopCallsForRemovedSession(it)
        }
    }

    override fun onCreate() {
        super.onCreate()
        authStore = AuthStore(SharedPreferencesKeyValueStore(this), AndroidKeystoreTokenCipher())
        restoreIncomingCall()
        IncomingCallNotifier(this).syncMissedAccounts(authStore.list().map { it.id })

        val registration = UnifiedPushAccountRegistration(this)
        authStore.list().forEach { account ->
            authStore.webPushConfig(account.id)?.let { config ->
                runCatching { registration.restore(account.id, config) }
            }
        }

        networkAvailability = NetworkAvailability(this)
        registerActivityLifecycleCallbacks(AppActivityVisibility)
        runCatching { TelecomCallController(AndroidTelecomRegistrar(this)).registerAudioOnly() }
        AuthSessionEvents.observe(authSessionObserver)
    }

    private fun restoreIncomingCall() {
        val incoming = IncomingCallController()
        val reclaimed = incoming.reclaimPending(this) { owner ->
            resolvePinnedCallSession(authStore, owner.key.accountId, owner.sessionBinding) != null
        }
        val invite = reclaimed?.let { key -> incoming.load(this)?.invite?.takeIf { it.key == key } } ?: return
        if (IncomingCallForegroundService.show(this, invite)) return

        val mode = currentIncomingCallPresentation(this)
        val shown = IncomingCallNotifier(this).presentIncoming(invite, mode) { notification ->
            getSystemService(NotificationManager::class.java)
                .notify(IncomingCallNotifier.NotificationId, notification)
        }
        if (shown) {
            IncomingRingingAcknowledger(this).acknowledge(invite)
            if (mode == IncomingCallPresentationMode.InApp) incoming.openScreen(this, invite)
            scheduleIncomingExpiry(this, invite)
        }
    }

    private fun stopCallsForRemovedSession(event: AuthSessionEvent) {
        val binding = CallSessionBinding.from(event.session)
        val incoming = IncomingCallController()
        val accountId = event.accountId
            ?: incoming.load(this)?.invite?.owner?.takeIf { it.sessionBinding == binding }?.key?.accountId
            ?: GlobalCallAdmission.current()?.owner?.takeIf { it.sessionBinding == binding }?.key?.accountId
            ?: return
        incoming.removeAccount(this, accountId, binding)
        CallForegroundService.accountRemoved(this, accountId, binding)
    }
}

internal fun cleanupWebPushAccount(context: Context, accountId: AccountId, session: Session? = null) {
    runCatching { ContactCache(SharedPreferencesKeyValueStore(context)).remove(accountId) }
    runCatching { UnifiedPushAccountRegistration(context).unsubscribe(accountId) }
    runCatching { PushRegistrationScheduler(context).cancel(accountId) }
    runCatching { IncomingCallNotifier(context).removeAccountMissedCount(accountId) }
    session?.let {
        val binding = CallSessionBinding.from(it)
        IncomingCallController().removeAccount(context, accountId, binding)
        CallForegroundService.accountRemoved(context, accountId, binding)
    }
}

internal object AppActivityVisibility : Application.ActivityLifecycleCallbacks {
    @Volatile
    private var resumedActivities = 0

    val isVisible: Boolean
        get() = resumedActivities > 0

    override fun onActivityResumed(activity: Activity) {
        resumedActivities++
    }

    override fun onActivityPaused(activity: Activity) {
        resumedActivities = (resumedActivities - 1).coerceAtLeast(0)
    }

    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
