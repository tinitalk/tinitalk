package org.tinitalk.telecom

import android.annotation.SuppressLint
import android.content.Context
import android.os.PowerManager
import java.io.Closeable

class ProximityController(context: Context) : Closeable {
    private val wakeLock = context.getSystemService(PowerManager::class.java)
        .takeIf { it.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK) }
        ?.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "TiniTalk:proximity")
        ?.apply { setReferenceCounted(false) }

    @SuppressLint("WakelockTimeout")
    fun setEnabled(enabled: Boolean) {
        val lock = wakeLock ?: return
        if (enabled && !lock.isHeld) {
            lock.acquire()
        } else if (!enabled && lock.isHeld) {
            lock.release()
        }
    }

    override fun close() {
        setEnabled(false)
    }
}
