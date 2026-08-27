package org.tinitalk.media

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network

internal class ActiveNetworkChangeDetector<T : Any>(
    initialNetwork: T?,
    private val onNetworkChanged: () -> Unit,
) : AutoCloseable {
    private var activeNetwork = initialNetwork
    private var closed = false

    @Synchronized
    fun onAvailable(network: T) {
        if (closed || activeNetwork == network) return
        activeNetwork = network
        onNetworkChanged()
    }

    @Synchronized
    fun onLost(network: T) {
        if (!closed && activeNetwork == network) activeNetwork = null
    }

    @Synchronized
    override fun close() {
        closed = true
        activeNetwork = null
    }
}

internal class DefaultNetworkObserver(
    context: Context,
    onNetworkChanged: () -> Unit,
) : AutoCloseable {
    private val connectivity = requireNotNull(context.getSystemService(ConnectivityManager::class.java))
    private val detector = ActiveNetworkChangeDetector(connectivity.activeNetwork, onNetworkChanged)
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = detector.onAvailable(network)
        override fun onLost(network: Network) = detector.onLost(network)
    }
    private var registered = false

    init {
        connectivity.registerDefaultNetworkCallback(callback)
        registered = true
    }

    @Synchronized
    override fun close() {
        if (!registered) return
        registered = false
        detector.close()
        connectivity.unregisterNetworkCallback(callback)
    }
}
