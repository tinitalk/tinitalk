package org.tinitalk.data.signal

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import org.tinitalk.data.Session
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executor
import java.util.concurrent.Executors

private val signalingCleanup = Executors.newSingleThreadExecutor { task ->
    Thread(task, "TiniTalk-SignalCleanup").apply { isDaemon = true }
}

internal fun closeSignalingConnection(
    socket: SignalConnection,
    client: OkHttpClient,
    cleanup: Executor = signalingCleanup,
) {
    // Invalidate callbacks immediately. OkHttp queues the WebSocket close frame.
    try { socket.close() } finally {
        // evictAll closes idle TLS sockets synchronously and can perform network
        // I/O, even though no request is being sent. Never run it on the UI thread.
        cleanup.execute {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}

internal object ApplicationSignaling {
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val connections = SharedSignalConnections(
        create = { session, device ->
            val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
            val socket = SignalSocket(client, session, deviceId = device)
            object : SignalConnection by socket {
                override fun close() {
                    closeSignalingConnection(socket, client)
                }
            }
        },
        dispatch = { task -> handler.post(task) },
        onCallbackFailure = { Log.e("TiniTalkSignal", "Signaling subscriber failed", it) },
    )

    fun acquire(session: Session, deviceId: String): SignalConnection = connections.acquire(session, deviceId)
}
