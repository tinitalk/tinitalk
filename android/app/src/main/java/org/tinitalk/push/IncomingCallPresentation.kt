package org.tinitalk.push

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import org.tinitalk.AppActivityVisibility
import org.tinitalk.call.AccountCallOwner

/** Invalidates queued notification hides when the incoming screen relinquishes presentation. */
internal object IncomingCallScreenState {
    private var owner: AccountCallOwner? = null
    private val observers = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()

    fun observe(observer: () -> Unit) { observers += observer }
    fun removeObserver(observer: () -> Unit) { observers -= observer }

    @Synchronized
    fun shown(owner: AccountCallOwner) {
        this.owner = owner
        observers.forEach { it() }
    }

    @Synchronized
    fun hidden(expectedOwner: AccountCallOwner? = null) {
        if (expectedOwner == null || owner == expectedOwner) {
            owner = null
            observers.forEach { it() }
        }
    }

    @Synchronized
    fun isShowing(owner: AccountCallOwner): Boolean = this.owner == owner
}

internal enum class IncomingCallPresentationMode {
    FullScreen,
    InApp,
    HeadsUp,
}

internal fun selectIncomingCallPresentation(
    screenInteractive: Boolean,
    keyguardLocked: Boolean,
    appVisible: Boolean,
): IncomingCallPresentationMode = when {
    !screenInteractive || keyguardLocked -> IncomingCallPresentationMode.FullScreen
    appVisible -> IncomingCallPresentationMode.InApp
    else -> IncomingCallPresentationMode.HeadsUp
}

internal fun currentIncomingCallPresentation(
    context: Context,
    appVisible: Boolean = AppActivityVisibility.isVisible,
): IncomingCallPresentationMode = selectIncomingCallPresentation(
    screenInteractive = context.getSystemService(PowerManager::class.java)?.isInteractive != false,
    keyguardLocked = context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true,
    appVisible = appVisible,
)
