package org.tinitalk.data.signal

import kotlin.math.min
import kotlin.random.Random

class ReconnectBackoff(
    private val jitterPercent: Int = 20,
    private val random: Random = Random.Default,
) {
    private var current = MIN_DELAY_MS

    fun nextDelayMillis(): Long {
        val base = current
        current = min(MAX_DELAY_MS, current * 2)
        if (jitterPercent == 0) return base
        val jitter = base * jitterPercent / 100
        return random.nextLong(base - jitter, base + jitter + 1)
    }

    fun reset() {
        current = MIN_DELAY_MS
    }

    companion object {
        const val MIN_DELAY_MS = 250L
        const val MAX_DELAY_MS = 5000L
    }
}
