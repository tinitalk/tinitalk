package org.tinitalk.telecom

import org.junit.Assert.assertEquals
import org.junit.Test

class CallHttpClientTest {
    @Test fun signalingClientSendsWebSocketPings() {
        val client = signalingHttpClient()
        assertEquals(20_000, client.pingIntervalMillis)
        client.dispatcher.executorService.shutdownNow()
    }
}
