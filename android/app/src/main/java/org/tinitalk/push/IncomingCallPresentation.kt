package org.tinitalk.push

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import org.tinitalk.AppActivityVisibility

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
