package org.tinitalk.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SessionCloseGateTest {
    @Test
    fun concurrentAndRepeatedCloseQueuesOneCameraCloseAttempt() {
        val gate = SessionCloseGate()
        val callersReady = CountDownLatch(2)
        val start = CountDownLatch(1)
        val cameraCloseAttempts = ConcurrentLinkedQueue<Unit>()
        val executor = Executors.newFixedThreadPool(2) { task ->
            Thread(task, "SessionCloseGateTest").apply { isDaemon = true }
        }
        repeat(2) {
            executor.execute {
                callersReady.countDown()
                start.await(1, TimeUnit.SECONDS)
                gate.runOnce { cameraCloseAttempts.add(Unit) }
            }
        }
        assertTrue(callersReady.await(1, TimeUnit.SECONDS))

        start.countDown()
        executor.shutdown()
        assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS))
        gate.runOnce { cameraCloseAttempts.add(Unit) }

        assertTrue(gate.closed)
        assertEquals(1, cameraCloseAttempts.size)
    }
}
