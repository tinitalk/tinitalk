package org.tinitalk.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class NativeResourceOwnerTest {
    @Test
    fun constructionFailureReleasesEveryResourceAndKeepsPrimaryFailure() {
        val released = mutableListOf<String>()
        val cleanupFailure = IllegalStateException("factory cleanup failed")
        val constructionFailure = IllegalStateException("transceiver creation failed")
        val owner = NativeResourceOwner()
        owner.own { released += "audio device" }
        owner.own {
            released += "factory"
            throw cleanupFailure
        }
        owner.own { released += "peer connection" }

        owner.close(constructionFailure)

        assertEquals(listOf("peer connection", "factory", "audio device"), released)
        assertArrayEquals(arrayOf(cleanupFailure), constructionFailure.suppressed)
    }

    @Test
    fun teardownAttemptsEveryReleaseBeforeRethrowingFirstFailure() {
        val released = mutableListOf<String>()
        val firstFailure = IllegalStateException("track cleanup failed")
        val laterFailure = IllegalStateException("factory cleanup failed")
        val owner = NativeResourceOwner()
        owner.own {
            released += "factory"
            throw laterFailure
        }
        owner.own { released += "source" }
        owner.own {
            released += "track"
            throw firstFailure
        }

        val thrown = assertThrows(IllegalStateException::class.java) { owner.close() }
        owner.close()

        assertSame(firstFailure, thrown)
        assertEquals(listOf("track", "source", "factory"), released)
        assertArrayEquals(arrayOf(laterFailure), thrown.suppressed)
    }
}
