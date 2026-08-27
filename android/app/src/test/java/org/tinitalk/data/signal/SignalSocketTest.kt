package org.tinitalk.data.signal

import com.google.gson.JsonObject
import org.tinitalk.data.Session
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import okio.ByteString
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SignalSocketTest {
    @Test
    fun connectsWithAuthAndDeviceHeadersAndNoQueryToken() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().withWebSocketUpgrade(CapturingWebSocketListener()))
            server.start()
            val client = OkHttpClient()
            val socket = SignalSocket(
                client,
                Session(server.url("/").toString(), "alice", "secret-token"),
                deviceId = "android-device-123",
            )

            socket.connect(onEvent = {})

            val request = server.takeRequest()
            assertEquals("/api/socket", request.path)
            assertNull(request.requestUrl?.query)
            assertEquals("Basic YWxpY2U6c2VjcmV0LXRva2Vu", request.getHeader("Authorization"))
            assertEquals("android-device-123", request.getHeader("X-TiniTalk-Device-ID"))
            socket.close()
            client.shutdown()
        }
    }

    @Test
    fun sendsEncodedEvent() {
        MockWebServer().use { server ->
            val listener = CapturingWebSocketListener()
            server.enqueue(MockResponse().withWebSocketUpgrade(listener))
            server.start()
            val client = OkHttpClient()
            val socket = SignalSocket(client, Session(server.url("/").toString(), "alice", "token"))
            val opened = CountDownLatch(1)
            socket.connect(onEvent = {}, onOpen = { opened.countDown() })
            listener.awaitOpen()
            assertTrue(opened.await(2, TimeUnit.SECONDS))

            socket.send(
                SignalEvent(
                    id = "018f7d51-3f90-7e63-b657-4a83a6a92000",
                    callId = "018f7d51-40a1-7bb5-a2d0-7e47f9182000",
                    type = "call.accept",
                    sentAt = 1787666400000,
                    payload = JsonObject(),
                )
            )

            server.takeRequest()
            val frame = listener.messages.poll(2, TimeUnit.SECONDS)
            assertEquals(true, frame.contains("\"type\":\"call.accept\""))
            socket.close()
            client.shutdown()
        }
    }

    @Test
    fun reportsServerErrorEnvelope() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().withWebSocketUpgrade(SendingWebSocketListener("""{"error":"ICE restart requested too often","code":"ice_restart_rate_limited","call_id":"call-1","event_id":"event-1","retry_after_ms":8750}""")))
            server.start()
            val client = OkHttpClient()
            lateinit var socket: SignalSocket
            socket = SignalSocket(client, Session(server.url("/").toString(), "alice", "token"))
            val error = LinkedBlockingQueue<SignalFailure>()

            socket.connect(
                onEvent = { throw AssertionError("unexpected signal event") },
                onError = {
                    error.add(it)
                    socket.close()
                },
            )

            assertEquals(
                SignalFailure(
                    message = "ICE restart requested too often",
                    code = "ice_restart_rate_limited",
                    callId = "call-1",
                    eventId = "event-1",
                    retryAfterMillis = 8_750L,
                ),
                error.poll(2, TimeUnit.SECONDS),
            )
            socket.close()
            client.shutdown()
        }
    }

    @Test
    fun reconnectsAfterNormalWebSocketClose() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().withWebSocketUpgrade(ClosingWebSocketListener()))
            server.enqueue(MockResponse().withWebSocketUpgrade(CapturingWebSocketListener()))
            server.start()
            val client = OkHttpClient()
            val socket = SignalSocket(client, Session(server.url("/").toString(), "alice", "token"))
            val opens = CountDownLatch(2)

            socket.connect(onEvent = {}, onOpen = { opens.countDown() })

            assertTrue(opens.await(3, TimeUnit.SECONDS))
            socket.close()
            client.shutdown()
        }
    }

    @Test
    fun networkHandoverImmediatelyReplacesInFlightSocket() {
        val client = OkHttpClient()
        val factory = FakeWebSocketFactory()
        val socket = SignalSocket(
            client,
            Session("https://talk.example.com", "alice", "token"),
            socketFactory = factory,
        )

        socket.connect(onEvent = {})
        val first = factory.connections.single()
        socket.reconnectNow()

        assertEquals(2, factory.connections.size)
        assertTrue(first.webSocket.cancelled)
        socket.close()
        client.shutdown()
    }

    @Test
    fun networkHandoverSupersedesPendingBackoffReconnect() {
        val client = OkHttpClient()
        val factory = FakeWebSocketFactory()
        val scheduler = FakeReconnectScheduler()
        val socket = SignalSocket(
            client,
            Session("https://talk.example.com", "alice", "token"),
            ReconnectBackoff(jitterPercent = 0),
            factory,
            scheduler::schedule,
        )

        socket.connect(onEvent = {})
        val first = factory.connections.single()
        first.listener.onFailure(first.webSocket, IOException("offline"), null)
        socket.reconnectNow()
        scheduler.runPending()

        assertEquals(2, factory.connections.size)
        socket.close()
        client.shutdown()
    }

    @Test
    fun callbackDuringSocketCreationIsProcessedAfterInstallation() {
        val client = OkHttpClient()
        val factory = FakeWebSocketFactory().apply {
            beforeReturn = { connection ->
                connection.listener.onOpen(connection.webSocket, response(connection.webSocket.request()))
            }
        }
        val socket = SignalSocket(
            client,
            Session("https://talk.example.com", "alice", "token"),
            socketFactory = factory,
        )
        var opens = 0

        socket.connect(onEvent = {}, onOpen = { opens++ })

        assertEquals(1, opens)
        assertTrue(socket.isOpen())
        socket.close()
        client.shutdown()
    }

    @Test
    fun failedPendingFlushIsRetriedOnReplacementSocket() {
        val client = OkHttpClient()
        val factory = FakeWebSocketFactory()
        val scheduler = FakeReconnectScheduler()
        val socket = SignalSocket(
            client,
            Session("https://talk.example.com", "alice", "token"),
            socketFactory = factory,
            reconnectScheduler = scheduler::schedule,
        )
        socket.connect(onEvent = {})
        socket.send(testEvent("call.accept"))
        val first = factory.connections.single()
        first.webSocket.allowSend = false

        first.listener.onOpen(first.webSocket, response(first.webSocket.request()))
        scheduler.runPending()
        val replacement = factory.connections.last()
        replacement.listener.onOpen(replacement.webSocket, response(replacement.webSocket.request()))

        assertEquals(1, replacement.webSocket.sent.size)
        assertTrue(replacement.webSocket.sent.single().contains("\"type\":\"call.accept\""))
        socket.close()
        client.shutdown()
    }

    @Test
    fun failedLiveSendIsQueuedAndRetriedOnReplacementSocket() {
        val client = OkHttpClient()
        val factory = FakeWebSocketFactory()
        val scheduler = FakeReconnectScheduler()
        val socket = SignalSocket(
            client,
            Session("https://talk.example.com", "alice", "token"),
            socketFactory = factory,
            reconnectScheduler = scheduler::schedule,
        )
        socket.connect(onEvent = {})
        val first = factory.connections.single()
        first.listener.onOpen(first.webSocket, response(first.webSocket.request()))
        first.webSocket.allowSend = false

        socket.send(testEvent("call.end"))
        scheduler.runPending()
        val replacement = factory.connections.last()
        replacement.listener.onOpen(replacement.webSocket, response(replacement.webSocket.request()))

        assertEquals(1, replacement.webSocket.sent.size)
        assertTrue(replacement.webSocket.sent.single().contains("\"type\":\"call.end\""))
        socket.close()
        client.shutdown()
    }

    @Test
    fun openStateTracksImmediateNetworkReplacement() {
        val client = OkHttpClient()
        val factory = FakeWebSocketFactory()
        val socket = SignalSocket(
            client,
            Session("https://talk.example.com", "alice", "token"),
            socketFactory = factory,
        )
        socket.connect(onEvent = {})
        val first = factory.connections.single()
        first.listener.onOpen(first.webSocket, response(first.webSocket.request()))
        assertTrue(socket.isOpen())

        socket.reconnectNow()

        assertFalse(socket.isOpen())
        val replacement = factory.connections.last()
        replacement.listener.onOpen(replacement.webSocket, response(replacement.webSocket.request()))
        assertTrue(socket.isOpen())
        socket.close()
        client.shutdown()
    }

    @Test
    fun staleCallbacksCannotReviveSupersededGeneration() {
        val client = OkHttpClient()
        val factory = FakeWebSocketFactory()
        val scheduler = FakeReconnectScheduler()
        val openedGenerations = mutableListOf<Long>()
        val socket = SignalSocket(
            client,
            Session("https://talk.example.com", "alice", "token"),
            socketFactory = factory,
            reconnectScheduler = scheduler::schedule,
        )
        socket.connect(onEvent = {}, onOpen = openedGenerations::add)
        val first = factory.connections.single()
        first.listener.onOpen(first.webSocket, response(first.webSocket.request()))
        val firstGeneration = openedGenerations.single()

        socket.reconnectNow()
        val replacement = factory.connections.last()
        replacement.listener.onOpen(replacement.webSocket, response(replacement.webSocket.request()))
        val replacementGeneration = openedGenerations.last()
        first.listener.onOpen(first.webSocket, response(first.webSocket.request()))
        first.listener.onFailure(first.webSocket, IOException("stale"), null)

        assertEquals(2, openedGenerations.size)
        assertFalse(socket.isOpen(firstGeneration))
        assertTrue(socket.isOpen(replacementGeneration))
        assertEquals(0, scheduler.pendingCount)
        socket.close()
        client.shutdown()
    }

    @Test
    fun closeSuppressesPendingReconnect() {
        val client = OkHttpClient()
        val factory = FakeWebSocketFactory()
        val scheduler = FakeReconnectScheduler()
        val socket = SignalSocket(
            client,
            Session("https://talk.example.com", "alice", "token"),
            socketFactory = factory,
            reconnectScheduler = scheduler::schedule,
        )
        socket.connect(onEvent = {})
        val first = factory.connections.single()
        first.listener.onFailure(first.webSocket, IOException("offline"), null)

        socket.close()
        scheduler.runPending()

        assertEquals(1, factory.connections.size)
        client.shutdown()
    }

    @Test
    fun automaticRetryUsesNewAttemptGeneration() {
        val client = OkHttpClient()
        val factory = FakeWebSocketFactory()
        val scheduler = FakeReconnectScheduler()
        val openedGenerations = mutableListOf<Long>()
        val socket = SignalSocket(
            client,
            Session("https://talk.example.com", "alice", "token"),
            socketFactory = factory,
            reconnectScheduler = scheduler::schedule,
        )
        socket.connect(onEvent = {}, onOpen = openedGenerations::add)
        val first = factory.connections.single()
        first.listener.onOpen(first.webSocket, response(first.webSocket.request()))
        first.listener.onFailure(first.webSocket, IOException("offline"), null)

        scheduler.runPending()
        val replacement = factory.connections.last()
        replacement.listener.onOpen(replacement.webSocket, response(replacement.webSocket.request()))

        assertNotEquals(openedGenerations.first(), openedGenerations.last())
        assertFalse(socket.isOpen(openedGenerations.first()))
        assertTrue(socket.isOpen(openedGenerations.last()))
        socket.close()
        client.shutdown()
    }

    @Test
    fun sendAfterCloseIsDiscarded() {
        val client = OkHttpClient()
        val factory = FakeWebSocketFactory()
        val socket = SignalSocket(
            client,
            Session("https://talk.example.com", "alice", "token"),
            socketFactory = factory,
        )
        socket.connect(onEvent = {})
        socket.close()

        socket.send(testEvent("call.end"))
        socket.connect(onEvent = {})
        val replacement = factory.connections.last()
        replacement.listener.onOpen(replacement.webSocket, response(replacement.webSocket.request()))

        assertTrue(replacement.webSocket.sent.isEmpty())
        socket.close()
        client.shutdown()
    }
}

private fun testEvent(type: String) = SignalEvent(
    id = "018f7d51-3f90-7e63-b657-4a83a6a92000",
    callId = "018f7d51-40a1-7bb5-a2d0-7e47f9182000",
    type = type,
    sentAt = 1787666400000,
    payload = JsonObject(),
)

private fun response(request: Request): Response = Response.Builder()
    .request(request)
    .protocol(Protocol.HTTP_1_1)
    .code(101)
    .message("Switching Protocols")
    .build()

private fun OkHttpClient.shutdown() {
    dispatcher.executorService.shutdownNow()
    connectionPool.evictAll()
}

private class CapturingWebSocketListener : WebSocketListener() {
    private val opened = LinkedBlockingQueue<Boolean>()
    val messages = LinkedBlockingQueue<String>()
    override fun onOpen(webSocket: WebSocket, response: Response) {
        opened += true
    }
    override fun onMessage(webSocket: WebSocket, text: String) {
        messages += text
        webSocket.close(1000, "ok")
    }
    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        webSocket.close(code, reason)
    }
    fun awaitOpen() {
        opened.poll(2, TimeUnit.SECONDS)
    }
}

private class SendingWebSocketListener(private val text: String) : WebSocketListener() {
    override fun onOpen(webSocket: WebSocket, response: Response) {
        webSocket.send(text)
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        webSocket.close(code, reason)
    }
}

private class ClosingWebSocketListener : WebSocketListener() {
    override fun onOpen(webSocket: WebSocket, response: Response) {
        Thread {
            Thread.sleep(50)
            webSocket.close(1000, "restart")
        }.start()
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        webSocket.close(code, reason)
    }
}

private class FakeWebSocketFactory : WebSocket.Factory {
    data class Connection(
        val webSocket: FakeWebSocket,
        val listener: WebSocketListener,
    )

    val connections = mutableListOf<Connection>()
    var beforeReturn: ((Connection) -> Unit)? = null

    override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
        val connection = Connection(FakeWebSocket(request), listener)
        connections += connection
        beforeReturn?.invoke(connection)
        return connection.webSocket
    }
}

private class FakeWebSocket(private val request: Request) : WebSocket {
    var cancelled = false
    var allowSend = true
    val sent = mutableListOf<String>()

    override fun request(): Request = request
    override fun queueSize(): Long = 0L
    override fun send(text: String): Boolean {
        if (cancelled || !allowSend) return false
        sent += text
        return true
    }
    override fun send(bytes: ByteString): Boolean = !cancelled && allowSend
    override fun close(code: Int, reason: String?): Boolean = true
    override fun cancel() {
        cancelled = true
    }
}

private class FakeReconnectScheduler {
    private val pending = mutableListOf<() -> Unit>()
    val pendingCount: Int get() = pending.size

    fun schedule(delayMillis: Long, action: () -> Unit) {
        assertTrue(delayMillis > 0L)
        pending += action
    }

    fun runPending() {
        pending.toList().also { pending.clear() }.forEach { it() }
    }
}
