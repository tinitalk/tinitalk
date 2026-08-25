package org.tinitalk.call

import org.tinitalk.data.Session
import org.tinitalk.data.signal.SignalSocket
import okhttp3.OkHttpClient

class CallRepository(
    private val session: Session,
    private val client: OkHttpClient = OkHttpClient(),
) {
    fun socket(): SignalSocket = SignalSocket(client, session)
}
