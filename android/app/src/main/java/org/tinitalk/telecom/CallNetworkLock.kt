package org.tinitalk.telecom

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build

internal interface NetworkLockBackend {
    val isHeld: Boolean
    fun acquire()
    fun release()
}

internal class CallNetworkLock(
    private val backend: NetworkLockBackend,
) : AutoCloseable {
    private var active = false

    fun setActive(active: Boolean) {
        if (this.active == active) return
        if (active) {
            backend.acquire()
        } else if (backend.isHeld) {
            backend.release()
        }
        this.active = active
    }

    override fun close() {
        setActive(false)
    }

    companion object {
        @Suppress("DEPRECATION")
        fun create(context: Context): CallNetworkLock {
            val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            val wifiLock = wifiManager.createWifiLock(mode, "TiniTalk:call").apply {
                setReferenceCounted(false)
            }
            return CallNetworkLock(AndroidNetworkLockBackend(wifiLock))
        }
    }
}

private class AndroidNetworkLockBackend(
    private val wifiLock: WifiManager.WifiLock,
) : NetworkLockBackend {
    override val isHeld: Boolean
        get() = wifiLock.isHeld

    override fun acquire() = wifiLock.acquire()

    override fun release() = wifiLock.release()
}
