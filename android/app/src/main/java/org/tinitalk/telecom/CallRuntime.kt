package org.tinitalk.telecom

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import org.tinitalk.call.CallCoordinator
import org.tinitalk.call.ForegroundCallController
import org.tinitalk.data.AccountId
import org.tinitalk.data.Session
import org.tinitalk.data.signal.SignalSocket
import org.tinitalk.media.CallMediaDispatcher
import org.tinitalk.media.DefaultNetworkObserver
import java.util.concurrent.TimeUnit

internal fun signalingHttpClient(): OkHttpClient =
    OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

/** Resources of one call. Local media can end before signaling is closed. */
internal class CallRuntime(
    private val httpClient: OkHttpClient,
    val socket: SignalSocket,
    val coordinator: CallCoordinator,
    media: ForegroundCallController,
    mediaDispatcher: CallMediaDispatcher,
) : AutoCloseable {
    @Volatile var media: ForegroundCallController? = media
        private set
    @Volatile var mediaDispatcher: CallMediaDispatcher? = mediaDispatcher
        private set

    private var networkObserver: DefaultNetworkObserver? = null
    private var networkLock: CallNetworkLock? = null
    private var closed = false

    // Lifecycle mutations are made by the service on the main thread. Media callbacks
    // read the volatile references to reject work after the local resources are detached.
    fun observeNetwork(context: Context, onChanged: () -> Unit) {
        check(!closed && networkObserver == null)
        networkObserver = DefaultNetworkObserver(context.applicationContext, onChanged)
    }

    fun setNetworkActive(context: Context, active: Boolean) {
        if (closed) return
        if (active) {
            val lock = networkLock ?: CallNetworkLock.create(context).also { networkLock = it }
            lock.setActive(true)
        } else networkLock?.setActive(false)
    }

    fun closeNetwork() {
        runCatching { networkLock?.close() }
        networkLock = null
        runCatching { networkObserver?.close() }
        networkObserver = null
    }

    fun releaseMedia() {
        val currentMedia = media
        val currentDispatcher = mediaDispatcher
        media = null
        mediaDispatcher = null
        if (currentMedia != null) {
            val cleanupDispatcher = currentDispatcher ?: CallMediaDispatcher()
            cleanupDispatcher.dispatch {
                runCatching { currentMedia.close() }.onFailure { failure ->
                    Log.e("TiniTalkCall", "failed to release call media", failure)
                }
            }
            cleanupDispatcher.close()
        } else currentDispatcher?.close()
    }

    override fun close() {
        if (closed) return
        closed = true
        closeNetwork()
        releaseMedia()
        closeSignaling(httpClient, socket)
    }

    companion object {
        fun create(
            session: Session,
            accountId: AccountId,
            deviceId: String,
            createMedia: (SignalSocket, CallCoordinator, CallMediaDispatcher) -> ForegroundCallController,
        ): CallRuntime {
            val client = signalingHttpClient()
            var socket: SignalSocket? = null
            var dispatcher: CallMediaDispatcher? = null
            try {
                val signaling = SignalSocket(client, session, deviceId = deviceId).also { socket = it }
                val coordinator = CallCoordinator(session.login, signaling, accountId = accountId)
                val mediaDispatcher = CallMediaDispatcher().also { dispatcher = it }
                val media = createMedia(signaling, coordinator, mediaDispatcher)
                return CallRuntime(client, signaling, coordinator, media, mediaDispatcher)
            } catch (failure: Throwable) {
                dispatcher?.close()
                closeSignaling(client, socket)
                throw failure
            }
        }
    }
}

private fun closeSignaling(client: OkHttpClient, socket: SignalSocket?) {
    runCatching { socket?.close() }
    runCatching { client.dispatcher.executorService.shutdownNow() }
    runCatching { client.connectionPool.evictAll() }
}
