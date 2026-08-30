package org.tinitalk.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import org.tinitalk.TinitalkApplication
import java.util.concurrent.CopyOnWriteArraySet

internal class NetworkAvailabilityState<T : Any>(
    initialNetwork: T?,
    private val onChanged: (Boolean) -> Unit,
) {
    private var activeNetwork = initialNetwork

    @Volatile
    var available: Boolean = initialNetwork != null
        private set

    @Synchronized
    fun onAvailable(network: T) {
        activeNetwork = network
        publish(true)
    }

    @Synchronized
    fun synchronize(network: T?) {
        activeNetwork = network
        publish(network != null)
    }

    @Synchronized
    fun onLost(network: T): Boolean {
        if (activeNetwork != network) return false
        activeNetwork = null
        return true
    }

    @Synchronized
    fun confirmUnavailable() {
        if (activeNetwork == null) publish(false)
    }

    private fun publish(available: Boolean) {
        if (this.available == available) return
        this.available = available
        onChanged(available)
    }
}

/**
 * Tracks an active default network, not Android's public-internet validation.
 * A self-hosted TiniTalk server may be reachable only through a LAN or VPN.
 */
class NetworkAvailability(context: Context) : AutoCloseable {
    private val connectivity = requireNotNull(context.getSystemService(ConnectivityManager::class.java))
    private val handler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<(Boolean) -> Unit>()
    private val state = NetworkAvailabilityState(connectivity.activeNetwork, ::publish)
    private val confirmOffline = Runnable { state.confirmUnavailable() }
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            handler.removeCallbacks(confirmOffline)
            state.onAvailable(network)
        }

        override fun onLost(network: Network) {
            if (state.onLost(network)) {
                handler.removeCallbacks(confirmOffline)
                handler.postDelayed(confirmOffline, OfflineConfirmationMillis)
            }
        }
    }
    private var registered = true

    val available: Boolean
        get() = state.available

    init {
        connectivity.registerDefaultNetworkCallback(callback, handler)
        // Close the small race between the initial snapshot and callback registration.
        state.synchronize(connectivity.activeNetwork)
    }

    /** Re-check at action time so a new call is never queued during the UI debounce window. */
    fun canStartNetworkAction(): Boolean = connectivity.activeNetwork != null

    fun observe(listener: (Boolean) -> Unit) {
        listeners += listener
        listener(available)
    }

    fun removeObserver(listener: (Boolean) -> Unit) {
        listeners -= listener
    }

    private fun publish(available: Boolean) {
        listeners.forEach { it(available) }
    }

    @Synchronized
    override fun close() {
        if (!registered) return
        registered = false
        handler.removeCallbacks(confirmOffline)
        connectivity.unregisterNetworkCallback(callback)
        listeners.clear()
    }

    private companion object {
        const val OfflineConfirmationMillis = 500L
    }
}

internal fun Context.networkAvailability(): NetworkAvailability =
    (applicationContext as TinitalkApplication).networkAvailability
