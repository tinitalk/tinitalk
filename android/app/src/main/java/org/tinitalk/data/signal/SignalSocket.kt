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

private const val DeviceIDHeader = "X-TiniTalk-Device-ID"

data class SignalFailure(
    val message: String,
    val code: String? = null,
    val callId: String? = null,
    val eventId: String? = null,
    val retryAfterMillis: Long? = null,
)

class SignalSocket(
    client: OkHttpClient,
    private val session: Session,
    private val backoff: ReconnectBackoff = ReconnectBackoff(),
    private val socketFactory: WebSocket.Factory = client,
    private val reconnectScheduler: (Long, () -> Unit) -> Unit = ::scheduleReconnect,
    private val deviceId: String = "",
) : SignalClient {
    private val pending = ArrayDeque<String>()
    private var closed = false
    private var opened = false
    private var generation = 0L
    private var callbacks: SignalCallbacks? = null
    private var attempt: SocketAttempt? = null

    fun connect(
        onEvent: (SequencedSignalEvent) -> Unit,
        onOpen: (Long) -> Unit = {},
        onDisconnected: (Long) -> Unit = {},
        onError: (SignalFailure) -> Unit = {},
    ) {
        val nextCallbacks = SignalCallbacks(onEvent, onOpen, onDisconnected, onError)
        val previous: WebSocket?
        val expectedGeneration: Long
        synchronized(pending) {
            closed = false
            opened = false
            callbacks = nextCallbacks
            previous = attempt?.socket
            attempt = null
            expectedGeneration = ++generation
        }
        previous?.cancel()
        open(nextCallbacks, expectedGeneration)
    }

    fun reconnectNow() {
        val currentCallbacks: SignalCallbacks
        val previous: WebSocket?
        val expectedGeneration: Long
        synchronized(pending) {
            if (closed) return
            currentCallbacks = callbacks ?: return
            previous = attempt?.socket
            val wasOpened = opened
            opened = false
            attempt = null
            backoff.reset()
            expectedGeneration = ++generation
            if (wasOpened) currentCallbacks.onDisconnected(expectedGeneration)
        }
        previous?.cancel()
        open(currentCallbacks, expectedGeneration)
    }

    fun isOpen(): Boolean = synchronized(pending) { !closed && opened }

    fun isOpen(expectedGeneration: Long): Boolean = synchronized(pending) {
        !closed && opened && generation == expectedGeneration
    }

    override fun send(event: SignalEvent) {
        val raw = event.encode()
        var failedSocket: WebSocket? = null
        var failedAttempt: SocketAttempt? = null
        var failureCallbacks: SignalCallbacks? = null
        synchronized(pending) {
            if (closed) return
            val currentAttempt = attempt
            val current = currentAttempt?.socket
            if (opened && current != null) {
                if (current.send(raw)) return
                opened = false
                failedSocket = current
                failedAttempt = currentAttempt
                failureCallbacks = callbacks
            }
            if (pending.size == SignalEvent.EVENT_BUFFER_LIMIT) pending.removeFirst()
            pending.addLast(raw)
        }
        val failed = failedAttempt
        val currentCallbacks = failureCallbacks
        if (failed != null && currentCallbacks != null) {
            handleFailure(failed, failedSocket!!, currentCallbacks)
        }
        failedSocket?.cancel()
    }

    fun close() {
        val current = synchronized(pending) {
            if (closed) return
            closed = true
            opened = false
            generation++
            callbacks = null
            pending.clear()
            attempt?.socket.also { attempt = null }
        }
        current?.close(1000, "closed")
    }

    private fun open(callbacks: SignalCallbacks, expectedGeneration: Long) {
        val currentAttempt = synchronized(pending) {
            if (closed || generation != expectedGeneration) return
            opened = false
            SocketAttempt(expectedGeneration).also { attempt = it }
        }
        val request = Request.Builder()
            .url(socketUrl())
            .header("Authorization", basicAuth())
            .apply {
                if (deviceId.isNotEmpty()) {
                    header(DeviceIDHeader, deviceId)
                }
            }
            .build()
        val nextSocket = socketFactory.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                dispatch(currentAttempt) { handleOpen(currentAttempt, webSocket, callbacks) }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                dispatch(currentAttempt) { handleMessage(currentAttempt, webSocket, text, callbacks) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                dispatch(currentAttempt) { handleFailure(currentAttempt, webSocket, callbacks) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                dispatch(currentAttempt) { handleFailure(currentAttempt, webSocket, callbacks) }
            }
        })
        val deferred: List<() -> Unit>
        val cancel = synchronized(pending) {
            if (closed || attempt !== currentAttempt || generation != expectedGeneration) {
                deferred = emptyList()
                true
            } else {
                currentAttempt.socket = nextSocket
                deferred = currentAttempt.deferred.toList()
                currentAttempt.deferred.clear()
                false
            }
        }
        if (cancel) {
            nextSocket.cancel()
        } else {
            deferred.forEach { it() }
        }
    }

    private fun dispatch(currentAttempt: SocketAttempt, action: () -> Unit) {
        val runNow = synchronized(pending) {
            if (closed || attempt !== currentAttempt || generation != currentAttempt.generation) return
            if (currentAttempt.socket == null) {
                currentAttempt.deferred.addLast(action)
                false
            } else {
                true
            }
        }
        if (runNow) action()
    }

    private fun handleOpen(
        currentAttempt: SocketAttempt,
        webSocket: WebSocket,
        callbacks: SignalCallbacks,
    ) {
        var flushFailed = false
        synchronized(pending) {
            if (!isCurrentLocked(currentAttempt, webSocket)) return
            backoff.reset()
            while (pending.isNotEmpty()) {
                if (!webSocket.send(pending.first())) {
                    flushFailed = true
                    opened = false
                    break
                }
                pending.removeFirst()
            }
            if (!flushFailed) {
                opened = true
                callbacks.onOpen(currentAttempt.generation)
            }
        }
        if (flushFailed) {
            handleFailure(currentAttempt, webSocket, callbacks)
            webSocket.cancel()
        }
    }

    private fun handleMessage(
        currentAttempt: SocketAttempt,
        webSocket: WebSocket,
        text: String,
        callbacks: SignalCallbacks,
    ) {
        val result = runCatching {
            val json = JsonParser.parseString(text).asJsonObject
            json["error"]?.asString?.let {
                return@runCatching ParsedSignal.Error(
                    SignalFailure(
                        message = it,
                        code = json["code"]?.asString,
                        callId = json["call_id"]?.asString,
                        eventId = json["event_id"]?.asString,
                        retryAfterMillis = json["retry_after_ms"]?.asLong,
                    ),
                )
            }
            val seq = json.remove("seq")?.asLong ?: 0L
            ParsedSignal.Event(SequencedSignalEvent(SignalEvent.decode(json.toString()), seq))
        }.getOrElse { ParsedSignal.Error(SignalFailure("invalid server event")) }

        synchronized(pending) {
            if (!isCurrentLocked(currentAttempt, webSocket)) return
            when (result) {
                is ParsedSignal.Event -> callbacks.onEvent(result.value)
                is ParsedSignal.Error -> callbacks.onError(result.value)
            }
        }
    }

    private fun handleFailure(
        currentAttempt: SocketAttempt,
        webSocket: WebSocket,
        callbacks: SignalCallbacks,
    ) {
        val delayMillis = synchronized(pending) {
            if (!isCurrentLocked(currentAttempt, webSocket)) return
            opened = false
            attempt = null
            callbacks.onDisconnected(currentAttempt.generation)
            backoff.nextDelayMillis()
        }
        reconnectScheduler(delayMillis) {
            val retryGeneration = synchronized(pending) {
                if (!closed && generation == currentAttempt.generation && attempt == null) {
                    ++generation
                } else {
                    null
                }
            }
            if (retryGeneration != null) open(callbacks, retryGeneration)
        }
    }

    private fun isCurrentLocked(currentAttempt: SocketAttempt, webSocket: WebSocket): Boolean =
        !closed && attempt === currentAttempt && currentAttempt.socket === webSocket &&
            generation == currentAttempt.generation

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

    private data class SignalCallbacks(
        val onEvent: (SequencedSignalEvent) -> Unit,
        val onOpen: (Long) -> Unit,
        val onDisconnected: (Long) -> Unit,
        val onError: (SignalFailure) -> Unit,
    )

    private class SocketAttempt(val generation: Long) {
        var socket: WebSocket? = null
        val deferred = ArrayDeque<() -> Unit>()
    }

    private sealed interface ParsedSignal {
        data class Event(val value: SequencedSignalEvent) : ParsedSignal
        data class Error(val value: SignalFailure) : ParsedSignal
    }
}

private fun scheduleReconnect(delayMillis: Long, action: () -> Unit) {
    Thread({
        try {
            Thread.sleep(delayMillis)
            action()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }, "signal-reconnect").apply {
        isDaemon = true
        start()
    }
}
