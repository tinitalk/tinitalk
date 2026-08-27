package org.tinitalk.data.signal

import com.google.gson.JsonObject
import org.tinitalk.data.Session
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SignalSocketTest {
    @Test
    fun connectsWithBasicAuthHeaderAndNoQueryToken() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().withWebSocketUpgrade(CapturingWebSocketListener()))
            server.start()
            val client = OkHttpClient()
            val socket = SignalSocket(
                client,
                Session(server.url("/").toString(), "alice", "secret-token"),
            )

            socket.connect(onEvent = {})

            val request = server.takeRequest()
            assertEquals("/api/socket", request.path)
            assertNull(request.requestUrl?.query)
            assertEquals("Basic YWxpY2U6c2VjcmV0LXRva2Vu", request.getHeader("Authorization"))
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
}

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
