package org.tinitalk.telecom

import org.junit.Assert.assertEquals
import org.junit.Test

class CallNetworkHandoverTest {
    @Test
    fun signalingReconnectStartsBeforeMediaRestart() {
        val order = mutableListOf<String>()

        migrateCallNetwork(
            reconnectSignaling = { order += "signaling" },
            restartMedia = { order += "media" },
        )

        assertEquals(listOf("signaling", "media"), order)
    }

    @Test
    fun nativeMediaCallbacksAreDeferredAndValidatedAtDelivery() {
        val queued = ArrayDeque<() -> Unit>()
        var current = true
        var deliveries = 0

        postCurrentMediaCallback(
            post = queued::addLast,
            isCurrent = { current },
        ) { deliveries++ }

        assertEquals(0, deliveries)
        queued.removeFirst().invoke()
        assertEquals(1, deliveries)

        postCurrentMediaCallback(
            post = queued::addLast,
            isCurrent = { current },
        ) { deliveries++ }
        current = false
        queued.removeFirst().invoke()

        assertEquals(1, deliveries)
    }
}
