package org.tinitalk

import android.app.Activity
import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import org.tinitalk.call.CallServiceState
import org.tinitalk.call.CallUiStateStore
import org.tinitalk.data.AndroidKeystoreTokenCipher
import org.tinitalk.data.AuthSessionEvent
import org.tinitalk.data.AuthSessionEvents
import org.tinitalk.data.AuthStore
import org.tinitalk.data.SharedPreferencesKeyValueStore
import org.tinitalk.push.IncomingCallForegroundService
import org.tinitalk.push.IncomingCallNotifier
import org.tinitalk.telecom.AndroidTelecomRegistrar
import org.tinitalk.telecom.CallForegroundService
import org.tinitalk.telecom.IncomingCallController
import org.tinitalk.telecom.TelecomCallController

class TinitalkApplication : Application() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var authStore: AuthStore
    private val authSessionObserver: (AuthSessionEvent) -> Unit = {
        mainHandler.post {
            if (authStore.load() == null) stopCallsAfterSessionReplacement()
        }
    }

    override fun onCreate() {
        super.onCreate()
        authStore = AuthStore(SharedPreferencesKeyValueStore(this), AndroidKeystoreTokenCipher())
        registerActivityLifecycleCallbacks(AppActivityVisibility)
        runCatching { TelecomCallController(AndroidTelecomRegistrar(this)).registerAudioOnly() }
        AuthSessionEvents.observe(authSessionObserver)
    }

    private fun stopCallsAfterSessionReplacement() {
        val incoming = IncomingCallController()
        val pendingCallId = incoming.load(this)?.invite?.callId
        val callIds = listOfNotNull(
            CallServiceState.snapshot().callId,
            CallUiStateStore.snapshot().callId,
            pendingCallId,
        ).distinct()

        stopService(Intent(this, CallForegroundService::class.java))
        IncomingCallForegroundService.stop(this)
        val notifier = IncomingCallNotifier(this)
        notifier.cancel()
        notifier.clearMissedCount()
        pendingCallId?.let { callId ->
            incoming.rememberTerminal(this, callId)
            incoming.clear(this, callId)
        }
        val telecom = TelecomCallController(AndroidTelecomRegistrar(this))
        callIds.forEach { callId -> runCatching { telecom.cancel(callId) } }
        getSystemService(NotificationManager::class.java).cancel(CallForegroundService.NotificationId)
        CallServiceState.reset()
        CallUiStateStore.reset()
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
