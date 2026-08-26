package org.tinitalk.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IceQueueTest {
    @Test
    fun buffersCandidatesUntilRemoteDescriptionIsReady() {
        val queue = IceQueue()
        val first = IceCandidateData("audio", 0, "candidate:1")
        val second = IceCandidateData("audio", 0, "candidate:2")

        assertTrue(queue.addOrBuffer(first).isEmpty())
        assertTrue(queue.addOrBuffer(second).isEmpty())

        assertEquals(listOf(first, second), queue.markRemoteDescriptionReady())
    }

    @Test
    fun returnsCandidatesImmediatelyAfterRemoteDescriptionIsReady() {
        val queue = IceQueue()
        queue.markRemoteDescriptionReady()
        val candidate = IceCandidateData("audio", 0, "candidate:1")

        assertEquals(listOf(candidate), queue.addOrBuffer(candidate))
    }

    @Test
    fun clearingQueueDropsBufferedCandidates() {
        val queue = IceQueue()
        queue.addOrBuffer(IceCandidateData("audio", 0, "candidate:1"))

        queue.clear()

        assertTrue(queue.markRemoteDescriptionReady().isEmpty())
    }

    @Test
    fun buffersCandidatesAgainForTheNextRemoteDescription() {
        val queue = IceQueue()
        queue.markRemoteDescriptionReady()
        queue.beginRemoteDescription()
        val candidate = IceCandidateData("audio", 0, "candidate:next-generation")

        assertTrue(queue.addOrBuffer(candidate).isEmpty())
        assertEquals(listOf(candidate), queue.markRemoteDescriptionReady())
    }
}
