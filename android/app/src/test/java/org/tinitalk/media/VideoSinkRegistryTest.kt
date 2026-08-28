package org.tinitalk.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class VideoSinkRegistryTest {
    @Test
    fun audioOnlySessionNeverBuildsARenderSourceForUnexpectedRemoteVideo() {
        assertFalse(shouldExposeRemoteVideo(videoAllowed = false, eglAvailable = false, sessionClosed = false))
        assertFalse(shouldExposeRemoteVideo(videoAllowed = true, eglAvailable = true, sessionClosed = true))
        assertTrue(shouldExposeRemoteVideo(videoAllowed = true, eglAvailable = true, sessionClosed = false))
    }

    @Test
    fun closeDetachesEverySinkBeforeNativeOwnerCanDisposeTheTrack() {
        val events = mutableListOf<String>()
        val registry = VideoSinkRegistry<String>(
            attachNative = { events += "attach:$it" },
            detachNative = { events += "detach:$it" },
        )
        assertTrue(registry.attach("remote"))
        assertTrue(registry.attach("preview"))

        registry.close()
        events += "dispose-track"

        assertEquals(
            listOf("attach:remote", "attach:preview", "detach:remote", "detach:preview", "dispose-track"),
            events,
        )
        assertFalse(registry.attach("late"))
    }

    @Test
    fun detachIsIdempotentAndCloseContinuesAfterNativeFailure() {
        val detached = mutableListOf<String>()
        val registry = VideoSinkRegistry<String>(
            attachNative = {},
            detachNative = {
                detached += it
                if (it == "broken") error("decoder already stopped")
            },
        )
        registry.attach("broken")
        registry.attach("healthy")

        registry.close()
        registry.detach("healthy")

        assertEquals(listOf("broken", "healthy"), detached)
    }

    @Test
    fun attachRacingCloseCannotLeaveANativeSinkAttached() {
        val attachEntered = CountDownLatch(1)
        val allowAttach = CountDownLatch(1)
        val detached = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val registry = VideoSinkRegistry<String>(
            attachNative = {
                attachEntered.countDown()
                assertTrue(allowAttach.await(2, TimeUnit.SECONDS))
            },
            detachNative = { detached.countDown() },
        )

        val attach = executor.submit<Boolean> { registry.attach("renderer") }
        assertTrue(attachEntered.await(2, TimeUnit.SECONDS))
        val close = executor.submit { registry.close() }
        allowAttach.countDown()

        assertTrue(attach.get(2, TimeUnit.SECONDS))
        close.get(2, TimeUnit.SECONDS)
        assertTrue(detached.await(2, TimeUnit.SECONDS))
        assertFalse(registry.attach("late"))
        executor.shutdownNow()
    }
}
