package org.tinitalk

import android.app.Activity
import android.app.Application
import android.os.Bundle
import org.tinitalk.telecom.AndroidTelecomRegistrar
import org.tinitalk.telecom.TelecomCallController

class TinitalkApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(AppActivityVisibility)
        runCatching { TelecomCallController(AndroidTelecomRegistrar(this)).registerAudioOnly() }
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
