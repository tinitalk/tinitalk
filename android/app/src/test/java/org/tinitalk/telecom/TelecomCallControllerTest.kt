package org.tinitalk.telecom

import org.junit.Assert.assertEquals
import org.junit.Test

class TelecomCallControllerTest {
    @Test
    fun registersAudioOnlyCapabilities() {
        val registrar = FakeTelecomRegistrar()
        val controller = TelecomCallController(registrar)

        controller.registerAudioOnly()

        assertEquals(TelecomCapabilities.AudioOnly, registrar.registered)
    }

    private class FakeTelecomRegistrar : TelecomRegistrar {
        var registered: TelecomCapabilities? = null
        override fun register(capabilities: TelecomCapabilities) {
            registered = capabilities
        }
    }
}
