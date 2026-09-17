package org.tinitalk.data.signal

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import org.tinitalk.data.Session
import java.util.concurrent.TimeUnit

internal object ApplicationSignaling {
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val connections = SharedSignalConnections(
        create = { session, device ->
            val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
            val socket = SignalSocket(client, session, deviceId = device)
            object : SignalConnection by socket {
                override fun close() {
                    try { socket.close() } finally {
                        client.dispatcher.executorService.shutdown()
                        client.connectionPool.evictAll()
                    }
                }
            }
        },
        dispatch = { task -> handler.post(task) },
        onCallbackFailure = { Log.e("TiniTalkSignal", "Signaling subscriber failed", it) },
    )

    fun acquire(session: Session, deviceId: String): SignalConnection = connections.acquire(session, deviceId)
}
