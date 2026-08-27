package org.tinitalk.push

import android.content.Context
import org.tinitalk.call.CallCoordinator
import org.tinitalk.data.AndroidKeystoreTokenCipher
import org.tinitalk.data.AuthStore
import org.tinitalk.data.SharedPreferencesKeyValueStore
import org.tinitalk.data.signal.SignalSocket
import org.tinitalk.telecom.signalingHttpClient
import java.io.Closeable
import okhttp3.OkHttpClient

class IncomingRingingAcknowledger(context: Context) : Closeable {
    private val context = context.applicationContext
    private var callId: String? = null
    private var socket: SignalSocket? = null
    private var httpClient: OkHttpClient? = null

    fun acknowledge(invite: IncomingInvite) {
        if (callId == invite.callId) return
        stop()
        val session = AuthStore(SharedPreferencesKeyValueStore(context), AndroidKeystoreTokenCipher()).load() ?: return
        val client = signalingHttpClient()
        val signal = SignalSocket(
            client,
            session,
            deviceId = DeviceRegistrar.deviceId(context),
        )
        CallCoordinator(session.login, signal).restoreIncoming(invite.callId, invite.lastSeq)
        signal.connect(onEvent = {})
        callId = invite.callId
        socket = signal
        httpClient = client
    }

    fun stop() {
        callId = null
        socket?.close()
        httpClient?.dispatcher?.executorService?.shutdownNow()
        httpClient?.connectionPool?.evictAll()
        socket = null
        httpClient = null
    }

    override fun close() = stop()
}
