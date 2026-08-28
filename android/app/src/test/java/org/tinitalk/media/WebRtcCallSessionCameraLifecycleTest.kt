package org.tinitalk.media

import org.junit.Assert.assertEquals
import org.junit.Test

class WebRtcCallSessionCameraLifecycleTest {
    @Test
    fun pauseBeforeTheFirstCameraControllerCompletesBothCallbacksExactlyOnce() {
        var detached = 0
        var released = 0

        pauseCameraOrComplete(
            controller = null,
            onDetached = { detached++ },
            onReleased = { released++ },
        )

        assertEquals(1, detached)
        assertEquals(1, released)
    }
}
