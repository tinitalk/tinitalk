package org.tinitalk.media

import org.tinitalk.call.ConnectionHealth
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionHealthClassifierTest {
    @Test
    fun `receiver buffer delay alone does not mark the network poor`() {
        val classifier = ConnectionHealthClassifier()
        val healthyNetwork = CallStats(
            rttMs = 10,
            jitterMs = 5,
            packetLossPercent = 0.0,
            concealedSamplesPercent = 0.0,
            jitterBufferTargetDelayMs = 380,
        )

        repeat(3) {
            assertEquals(
                ConnectionHealth.Good,
                classifier.update(healthyNetwork, ConnectionHealth.Good),
            )
        }
    }

    @Test
    fun `quality becomes poor only when sustained and recovers without overriding reconnect`() {
        val classifier = ConnectionHealthClassifier()
        val good = CallStats(rttMs = 90, jitterMs = 12, packetLossPercent = 0.5)
        val poor = CallStats(rttMs = 720, jitterMs = 140, packetLossPercent = 12.0)
        val steps = listOf(
            Step("good", good, ConnectionHealth.Good, ConnectionHealth.Good),
            Step("first poor sample", poor, ConnectionHealth.Good, ConnectionHealth.Good),
            Step("second poor sample", poor, ConnectionHealth.Good, ConnectionHealth.Good),
            Step("sustained poor", poor, ConnectionHealth.Good, ConnectionHealth.Poor),
            Step("first recovery sample", good, ConnectionHealth.Poor, ConnectionHealth.Poor),
            Step("recovered", good, ConnectionHealth.Poor, ConnectionHealth.Good),
            Step("reconnecting wins immediately", poor, ConnectionHealth.Reconnecting, ConnectionHealth.Reconnecting),
            Step("good after reconnect", good, ConnectionHealth.Good, ConnectionHealth.Good),
        )

        steps.forEach { step ->
            assertEquals(step.name, step.expected, classifier.update(step.stats, step.current))
        }
    }

    private data class Step(
        val name: String,
        val stats: CallStats,
        val current: ConnectionHealth,
        val expected: ConnectionHealth,
    )
}
