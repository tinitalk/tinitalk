package org.tinitalk.data.signal

import com.google.gson.JsonParser
import org.tinitalk.call.SequencedSignalEvent
import org.tinitalk.call.SignalClient
import org.tinitalk.data.Session
import java.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

class SignalSocket(
	private val client: OkHttpClient,
	private val session: Session,
	private val backoff: ReconnectBackoff = ReconnectBackoff(),
) : SignalClient {
	private var socket: WebSocket? = null
	@Volatile private var closed = false

	fun connect(onEvent: (SequencedSignalEvent) -> Unit) {
		closed = false
		val request = Request.Builder()
			.url(socketUrl())
			.header("Authorization", basicAuth())
			.build()
		socket = client.newWebSocket(request, object : WebSocketListener() {
			override fun onOpen(webSocket: WebSocket, response: Response) {
				backoff.reset()
			}

			override fun onMessage(webSocket: WebSocket, text: String) {
				val json = JsonParser.parseString(text).asJsonObject
				val seq = json.remove("seq")?.asLong ?: 0L
				onEvent(SequencedSignalEvent(SignalEvent.decode(json.toString()), seq))
			}

			override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
				if (closed) return
				Thread {
					Thread.sleep(backoff.nextDelayMillis())
					if (!closed) connect(onEvent)
				}.start()
			}
		})
	}

    override fun send(event: SignalEvent) {
        socket?.send(event.encode())
    }

	fun close() {
		closed = true
		socket?.close(1000, "closed")
		socket = null
	}

    private fun socketUrl(): String {
        val base = session.url.trimEnd('/')
        val ws = when {
            base.startsWith("https://") -> "wss://" + base.removePrefix("https://")
            base.startsWith("http://") -> "ws://" + base.removePrefix("http://")
            else -> base
        }
        return "$ws/api/socket"
    }

	private fun basicAuth(): String {
		val raw = "${session.login}:${session.token}".toByteArray(Charsets.UTF_8)
		return "Basic " + Base64.getEncoder().encodeToString(raw)
	}
}
