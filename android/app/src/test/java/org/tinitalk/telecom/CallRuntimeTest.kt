package org.tinitalk.telecom

import android.app.Application
import android.net.ConnectivityManager
import okhttp3.*
import okio.ByteString
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.tinitalk.call.CallCoordinator
import org.tinitalk.call.ForegroundCallController
import org.tinitalk.data.AccountId
import org.tinitalk.data.Session
import org.tinitalk.data.signal.SignalSocket
import org.tinitalk.media.CallMediaDispatcher
import org.tinitalk.media.CancellableTask
import org.tinitalk.media.TaskScheduler
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = org.tinitalk.i18n.LocalizedTestApplication::class)
class CallRuntimeTest {
    private val fixtures = mutableListOf<Fixture>()

    @After fun cleanup() {
        fixtures.forEach {
            it.runtime.close()
            // Also release fixture resources when a lifecycle assertion fails.
            it.media.close()
            it.dispatcher.close()
            runCatching { it.socket.close() }
            it.client.dispatcher.executorService.shutdownNow()
            it.client.connectionPool.evictAll()
        }
    }

    @Test fun mediaIsDetachedBeforeQueuedCleanupAndSignalingRemainsUsable() {
        val f = fixture()
        val unblock = CountDownLatch(1)
        try {
            assertTrue(f.dispatcher.dispatch {
                unblock.await(3, TimeUnit.SECONDS)
                f.events.add("queued operation")
            })
            f.coordinator.startCall("bob", "018f7d51-40a1-7bb5-a2d0-7e47f9182000")
            f.runtime.releaseMedia()
            assertNull(f.runtime.media)
            assertNull(f.runtime.mediaDispatcher)
            assertFalse(f.dispatcher.dispatch { fail("dispatcher must be closed") })
            assertEquals(0, f.mediaCloses.get())
            assertTrue(f.socket.isOpen())
            assertFalse(f.client.dispatcher.executorService.isShutdown)
            // A terminal event still has a live signaling connection after local media release.
            f.coordinator.cancel()
            assertTrue(f.webSocket.sent.last().contains("call.cancel"))
            unblock.countDown()
            assertTrue(f.mediaClosed.await(3, TimeUnit.SECONDS))
            assertEquals(listOf("queued operation", "media close"), f.events.toList())
            assertEquals("TiniTalkCallMedia", f.closeThread)
        } finally {
            unblock.countDown()
        }
    }

    @Test fun fullCloseIsIdempotentAndHttpCleanupSurvivesSocketCloseFailure() {
        for (failClose in listOf(false, true)) {
            val f = fixture()
            f.webSocket.failClose = failClose
            f.runtime.close()
            f.runtime.close()
            assertNull(f.runtime.media)
            assertNull(f.runtime.mediaDispatcher)
            assertFalse(f.socket.isOpen())
            assertEquals(1, f.webSocket.closes)
            assertTrue(f.client.dispatcher.executorService.isShutdown)
            assertTrue(f.mediaClosed.await(3, TimeUnit.SECONDS))
            assertEquals(1, f.mediaCloses.get())
        }
    }

    @Test fun emergencyCleanupDoesNotWaitBehindBlockedMediaQueue() {
        val f = fixture()
        val unblock = CountDownLatch(1)
        val started = CountDownLatch(1)
        try {
            assertTrue(f.dispatcher.dispatch {
                started.countDown()
                unblock.await(10, TimeUnit.SECONDS)
            })
            assertTrue(started.await(2, TimeUnit.SECONDS))
            f.runtime.releaseMedia(bypassQueue = true)
            assertNull(f.runtime.media)
            assertFalse(f.dispatcher.dispatch { fail("detached queue accepted work") })
            assertTrue("close must run while the original queue remains blocked", f.mediaClosed.await(2, TimeUnit.SECONDS))
            assertEquals(1L, unblock.count)
            assertEquals(1, f.mediaCloses.get())
            assertTrue(f.socket.isOpen())
            f.runtime.releaseMedia(bypassQueue = true)
            assertEquals(1, f.mediaCloses.get())
        } finally { unblock.countDown() }
    }

    @Test fun cleanupStillRunsWhenDispatcherAlreadyRejectsWork() {
        val f = fixture()
        f.dispatcher.close()
        f.runtime.releaseMedia()
        assertTrue(f.mediaClosed.await(2, TimeUnit.SECONDS))
        assertEquals(1, f.mediaCloses.get())
    }

    @Test fun closingNetworkUnregistersOnlyThisCallsObserver() {
        val context = RuntimeEnvironment.getApplication()
        val connectivity = shadowOf(context.getSystemService(ConnectivityManager::class.java))
        val first = fixture()
        val second = fixture()
        val before = connectivity.networkCallbacks.size
        first.runtime.observeNetwork(context) {}
        second.runtime.observeNetwork(context) {}
        assertEquals(before + 2, connectivity.networkCallbacks.size)
        first.runtime.closeNetwork()
        first.runtime.closeNetwork()
        assertEquals(before + 1, connectivity.networkCallbacks.size)
        second.runtime.close()
        assertEquals(before, connectivity.networkCallbacks.size)
    }

    @Test fun lateCleanupOfPreviousRuntimeDoesNotReleaseNewCall() {
        val first = fixture()
        val next = fixture()
        first.runtime.close()
        first.runtime.releaseMedia()
        assertNull(first.runtime.media)
        assertFalse(first.socket.isOpen())
        assertSame(next.media, next.runtime.media)
        assertSame(next.dispatcher, next.runtime.mediaDispatcher)
        assertTrue(next.socket.isOpen())
        assertFalse(next.client.dispatcher.executorService.isShutdown)
        assertEquals(0, next.mediaCloses.get())
    }

    @Test fun mediaConstructionFailureReleasesDispatcherAndPreservesCause() {
        val failure = IllegalStateException("media setup failed")
        var dispatcher: CallMediaDispatcher? = null
        val result = runCatching {
            CallRuntime.create(Session("https://call.example", "alice", "token"), AccountId("a"), "device") {
                    _, _, createdDispatcher ->
                dispatcher = createdDispatcher
                throw failure
            }
        }
        assertSame(failure, result.exceptionOrNull())
        val allocated = requireNotNull(dispatcher)
        try {
            assertFalse(allocated.dispatch { fail("failed runtime must release its dispatcher") })
        } finally {
            allocated.close()
        }
    }

    private fun fixture() = Fixture().also(fixtures::add)

    private class Fixture {
        val client = signalingHttpClient()
        val webSocket = RecordingWebSocket()
        private lateinit var listener: WebSocketListener
        val socket = SignalSocket(client, Session("https://call.example", "alice", "token"),
            socketFactory = WebSocket.Factory { request, callback ->
                webSocket.requestValue = request
                listener = callback
                webSocket
            })
        val coordinator = CallCoordinator("alice", socket, accountId = AccountId("a"))
        val dispatcher = CallMediaDispatcher()
        val mediaCloses = AtomicInteger()
        val mediaClosed = CountDownLatch(1)
        val events = Collections.synchronizedList(mutableListOf<String>())
        @Volatile var closeThread: String? = null
        val media = ForegroundCallController(
            signal = socket,
            accountId = AccountId("a"),
            mediaFactory = { _, _, _, _, _, _ -> error("No WebRTC needed") },
            scheduler = object : TaskScheduler {
                override fun schedule(delayMillis: Long, action: () -> Unit) = CancellableTask {}
                override fun close() {
                    mediaCloses.incrementAndGet()
                    closeThread = Thread.currentThread().name
                    events.add("media close")
                    mediaClosed.countDown()
                }
            },
        )
        val runtime = CallRuntime(client, socket, coordinator, media, dispatcher)
        init {
            socket.connect(onEvent = {})
            listener.onOpen(webSocket, Response.Builder().request(webSocket.request())
                .protocol(Protocol.HTTP_1_1).code(101).message("Switching Protocols")
                .header("X-TiniTalk-Signal-Protocol", "2").build())
        }
    }

    private class RecordingWebSocket : WebSocket {
        lateinit var requestValue: Request
        val sent = mutableListOf<String>()
        var closes = 0
        var failClose = false
        override fun request() = requestValue
        override fun queueSize() = 0L
        override fun send(text: String): Boolean { sent += text; return true }
        override fun send(bytes: ByteString) = true
        override fun cancel() = Unit
        override fun close(code: Int, reason: String?): Boolean {
            closes++
            check(!failClose) { "socket close failed" }
            return true
        }
    }
}
