package org.tinitalk.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class CallMediaDispatcherTest {
    @Test
    fun dispatchReturnsImmediatelyAndRunsTasksSerially() {
        val dispatcher = CallMediaDispatcher()
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val callerReturned = CountDownLatch(1)
        val secondFinished = CountDownLatch(1)
        val order = mutableListOf<String>()
        val caller = thread(name = "media-dispatch-test-caller") {
            dispatcher.dispatch {
                order += "first"
                firstStarted.countDown()
                releaseFirst.await(2, TimeUnit.SECONDS)
            }
            callerReturned.countDown()
            dispatcher.dispatch {
                order += "second"
                secondFinished.countDown()
            }
        }

        try {
            assertTrue(firstStarted.await(1, TimeUnit.SECONDS))
            assertTrue(callerReturned.await(1, TimeUnit.SECONDS))
            assertFalse(secondFinished.await(100, TimeUnit.MILLISECONDS))

            releaseFirst.countDown()
            assertTrue(secondFinished.await(1, TimeUnit.SECONDS))
            assertEquals(listOf("first", "second"), order)
            dispatcher.close()
            assertFalse(dispatcher.dispatch { order += "late" })
        } finally {
            releaseFirst.countDown()
            caller.join(1_000)
            dispatcher.close()
        }
    }
}
