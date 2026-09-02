package org.tinitalk

import android.app.Activity
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import org.tinitalk.call.CallSessionBinding
import org.tinitalk.call.GlobalCallAdmission
import org.tinitalk.call.resolvePinnedCallSession
import org.tinitalk.data.AccountId
import org.tinitalk.data.AndroidKeystoreTokenCipher
import org.tinitalk.data.AuthSessionEvent
import org.tinitalk.data.AuthSessionEvents
import org.tinitalk.data.AuthStore
import org.tinitalk.data.ContactCache
import org.tinitalk.data.ContactPhotoAccountLifecycle
import org.tinitalk.data.ContactPhotoProcessor
import org.tinitalk.data.ContactPhotoStore
import org.tinitalk.data.Session
import org.tinitalk.data.SharedPreferencesKeyValueStore
import org.tinitalk.data.normalizeServerUrl
import org.tinitalk.network.NetworkAvailability
import org.tinitalk.push.IncomingCallForegroundService
import org.tinitalk.push.IncomingCallNotifier
import org.tinitalk.push.IncomingCallPresentationMode
import org.tinitalk.push.IncomingRingingAcknowledger
import org.tinitalk.push.ContactPhotoNotificationLoader
import org.tinitalk.push.PushRegistrationScheduler
import org.tinitalk.push.UnifiedPushAccountRegistration
import org.tinitalk.push.currentIncomingCallPresentation
import org.tinitalk.push.scheduleIncomingExpiry
import org.tinitalk.telecom.AndroidTelecomRegistrar
import org.tinitalk.telecom.CallForegroundService
import org.tinitalk.telecom.IncomingCallController
import org.tinitalk.telecom.TelecomCallController
import java.util.concurrent.Executors

class TinitalkApplication : Application() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var authStore: AuthStore
    lateinit var contactPhotoStore: ContactPhotoStore
        private set
    lateinit var contactPhotoProcessor: ContactPhotoProcessor
        private set
    lateinit var contactPhotoNotificationLoader: ContactPhotoNotificationLoader
        private set
    lateinit var contactPhotoAccountLifecycle: ContactPhotoAccountLifecycle
        private set
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
        contactPhotoStore = ContactPhotoStore(
            filesDir.resolve("contact_photos"),
            object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
                override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
            },
        )
        contactPhotoProcessor = ContactPhotoProcessor(this)
        contactPhotoNotificationLoader = ContactPhotoNotificationLoader(
            contactPhotoStore,
            Executors.newSingleThreadExecutor { task ->
                Thread(task, "tinitalk-contact-photo-notification").apply { isDaemon = true }
            },
        )
        authStore = AuthStore(SharedPreferencesKeyValueStore(this), AndroidKeystoreTokenCipher())
        contactPhotoAccountLifecycle = ContactPhotoAccountLifecycle(contactPhotoStore) { serverUrl ->
            authStore.list().any { account -> normalizeServerUrl(account.session.url) == normalizeServerUrl(serverUrl) }
        }
        authStore.list().forEach { account -> contactPhotoAccountLifecycle.activateServer(account.session.url) }
        Thread({
            contactPhotoStore.purgeTrash()
            contactPhotoProcessor.purgeDrafts()
        }, "tinitalk-contact-photo-trash").start()
        restoreIncomingCall()
        IncomingCallNotifier(this).syncMissedAccounts(authStore.list().map { it.id })

        Thread({
            runCatching {
                val scheduler = PushRegistrationScheduler(this)
                authStore.list().forEach { account -> scheduler.enqueueRestore(account.id) }
            }
        }, "tinitalk-push-restore").start()

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

internal fun contactPhotoStore(context: Context): ContactPhotoStore =
    (context.applicationContext as TinitalkApplication).contactPhotoStore

internal fun contactPhotoNotificationLoader(context: Context): ContactPhotoNotificationLoader =
    (context.applicationContext as TinitalkApplication).contactPhotoNotificationLoader

internal fun contactPhotoAccountLifecycle(context: Context): ContactPhotoAccountLifecycle =
    (context.applicationContext as TinitalkApplication).contactPhotoAccountLifecycle

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
