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
	@Volatile private var opened = false
	private val pending = ArrayDeque<String>()

	fun connect(
		onEvent: (SequencedSignalEvent) -> Unit,
		onOpen: () -> Unit = {},
		onDisconnected: () -> Unit = {},
	) {
		closed = false
		opened = false
		val request = Request.Builder()
			.url(socketUrl())
			.header("Authorization", basicAuth())
			.build()
		socket = client.newWebSocket(request, object : WebSocketListener() {
			override fun onOpen(webSocket: WebSocket, response: Response) {
				backoff.reset()
				synchronized(pending) {
					pending.forEach(webSocket::send)
					pending.clear()
					opened = true
				}
				onOpen()
			}

			override fun onMessage(webSocket: WebSocket, text: String) {
				val json = JsonParser.parseString(text).asJsonObject
				val seq = json.remove("seq")?.asLong ?: 0L
				onEvent(SequencedSignalEvent(SignalEvent.decode(json.toString()), seq))
			}

			override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
				if (closed) return
				synchronized(pending) { opened = false }
				onDisconnected()
				Thread {
					Thread.sleep(backoff.nextDelayMillis())
					if (!closed) connect(onEvent, onOpen, onDisconnected)
				}.start()
			}
		})
	}

    override fun send(event: SignalEvent) {
		val raw = event.encode()
		synchronized(pending) {
			val current = socket
			if (opened && current != null && current.send(raw)) return
			if (pending.size == SignalEvent.EVENT_BUFFER_LIMIT) pending.removeFirst()
			pending.addLast(raw)
		}
    }

	fun close() {
		closed = true
		synchronized(pending) { opened = false }
		socket?.close(1000, "closed")
		socket = null
		synchronized(pending) { pending.clear() }
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
