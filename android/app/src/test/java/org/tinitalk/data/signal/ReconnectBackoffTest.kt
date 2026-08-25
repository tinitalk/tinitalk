package org.tinitalk.data.signal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectBackoffTest {
    @Test
    fun growsFromMinimumToMaximumWithJitterBounds() {
        val backoff = ReconnectBackoff(jitterPercent = 0)

        assertEquals(250L, backoff.nextDelayMillis())
        assertEquals(500L, backoff.nextDelayMillis())
        assertEquals(1000L, backoff.nextDelayMillis())
        repeat(10) { backoff.nextDelayMillis() }
        assertEquals(5000L, backoff.nextDelayMillis())
    }

    @Test
    fun resetReturnsToMinimum() {
        val backoff = ReconnectBackoff(jitterPercent = 0)
        backoff.nextDelayMillis()
        backoff.nextDelayMillis()

        backoff.reset()

        assertEquals(250L, backoff.nextDelayMillis())
    }

    @Test
    fun jitterStaysWithinRange() {
        val backoff = ReconnectBackoff(jitterPercent = 20)
        val value = backoff.nextDelayMillis()

        assertTrue(value in 200L..300L)
    }
}
