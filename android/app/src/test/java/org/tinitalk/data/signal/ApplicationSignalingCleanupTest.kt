package org.tinitalk.data.signal

import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.tinitalk.data.Session
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ApplicationSignalingCleanupTest {
    @Test fun closesSubscriptionImmediatelyButDefersHttpPoolCleanup() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("ok"))
            val client = OkHttpClient()
            try {
                client.newCall(Request.Builder().url(server.url("/")).build()).execute().use { it.body.string() }
                assertEquals(1, client.connectionPool.idleConnectionCount())
                var closed = false
                val socket = object : SignalConnection by SignalSocket(client, Session("https://example.org", "alice", "token")) {
                    override fun close() { closed = true }
                }
                val tasks = LinkedBlockingQueue<Runnable>()
                closeSignalingConnection(socket, client, Executor { tasks.add(it) })
                assertTrue(closed)
                assertFalse(client.dispatcher.executorService.isShutdown)
                assertEquals(1, client.connectionPool.idleConnectionCount())
                assertEquals(1, tasks.size)
                val worker = Thread(tasks.remove())
                worker.start()
                worker.join(5_000)
                assertFalse(worker.isAlive)
                assertTrue(client.dispatcher.executorService.isShutdown)
                assertEquals(0, client.connectionPool.connectionCount())
            } finally {
                client.dispatcher.executorService.shutdownNow()
                client.connectionPool.evictAll()
            }
        }
    }

    @Test fun defaultCleanupRunsOutsideCallingThread() {
        val caller = Thread.currentThread()
        val cleanupThread = AtomicReference<Thread>()
        val finished = CountDownLatch(1)
        val dispatcher = object : ThreadPoolExecutor(0, 1, 1, TimeUnit.SECONDS, LinkedBlockingQueue()) {
            override fun shutdown() {
                cleanupThread.set(Thread.currentThread())
                super.shutdown()
                finished.countDown()
            }
        }
        val client = OkHttpClient.Builder().dispatcher(Dispatcher(dispatcher)).build()
        try {
            closeSignalingConnection(SignalSocket(client, Session("https://example.org", "alice", "token")), client)
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            assertNotSame(caller, cleanupThread.get())
        } finally {
            dispatcher.shutdownNow()
            client.connectionPool.evictAll()
        }
    }
}
