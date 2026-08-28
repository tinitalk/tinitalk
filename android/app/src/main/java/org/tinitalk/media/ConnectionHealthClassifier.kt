package org.tinitalk.media

import org.tinitalk.call.ConnectionHealth

class ConnectionHealthClassifier {
    private var poorSamples = 0
    private var goodSamples = 0

    fun update(stats: CallStats, current: ConnectionHealth): ConnectionHealth {
        if (current == ConnectionHealth.Connecting || current == ConnectionHealth.Reconnecting) {
            reset()
            return current
        }

        if (stats.isPoorNetworkSample()) {
            poorSamples++
            goodSamples = 0
            return if (current == ConnectionHealth.Poor || poorSamples >= PoorSamplesRequired) {
                ConnectionHealth.Poor
            } else {
                ConnectionHealth.Good
            }
        }

        goodSamples++
        poorSamples = 0
        return if (current == ConnectionHealth.Poor && goodSamples < GoodSamplesRequired) {
            ConnectionHealth.Poor
        } else {
            ConnectionHealth.Good
        }
    }

    fun reset() {
        poorSamples = 0
        goodSamples = 0
    }

    private companion object {
        const val PoorSamplesRequired = 3
        const val GoodSamplesRequired = 2
    }
}

internal fun CallStats.isPoorNetworkSample(): Boolean =
    rttMs >= 550L ||
        jitterMs >= 100L ||
        packetLossPercent >= 8.0 ||
        concealedSamplesPercent >= 8.0 ||
        jitterBufferTargetDelayMs >= 250L
