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
}
