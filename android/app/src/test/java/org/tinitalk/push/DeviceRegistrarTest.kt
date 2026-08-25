package org.tinitalk.push

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceRegistrarTest {
    @Test
    fun registersCurrentFcmTokenForDevice() {
        var savedDevice = ""
        var savedToken = ""
        val registrar = DeviceRegistrar(
            tokenProvider = { callback -> callback("fcm-token") },
            register = { deviceId, token ->
                savedDevice = deviceId
                savedToken = token
            },
        )

        registrar.register("device-1")

        assertEquals("device-1", savedDevice)
        assertEquals("fcm-token", savedToken)
    }
}
