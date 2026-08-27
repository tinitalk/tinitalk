package org.tinitalk.media

import org.junit.Assert.assertEquals
import org.junit.Test

class DefaultNetworkObserverTest {
    @Test
    fun sameInitialNetworkDoesNotTriggerHandover() {
        var changes = 0
        val detector = ActiveNetworkChangeDetector("wifi") { changes++ }

        detector.onAvailable("wifi")
        detector.onAvailable("wifi")

        assertEquals(0, changes)
    }

    @Test
    fun newDefaultNetworkTriggersOneHandoverForEitherCallbackOrder() {
        var changes = 0
        val detector = ActiveNetworkChangeDetector("wifi") { changes++ }

        detector.onAvailable("mobile")
        detector.onLost("wifi")
        detector.onLost("mobile")
        detector.onAvailable("wifi")
        detector.onAvailable("wifi")

        assertEquals(2, changes)
    }

    @Test
    fun firstNetworkAfterOfflineStartupTriggersRecovery() {
        var changes = 0
        val detector = ActiveNetworkChangeDetector<String>(null) { changes++ }

        detector.onAvailable("mobile")

        assertEquals(1, changes)
    }

    @Test
    fun closedDetectorIgnoresQueuedCallbacks() {
        var changes = 0
        val detector = ActiveNetworkChangeDetector("wifi") { changes++ }

        detector.close()
        detector.onLost("wifi")
        detector.onAvailable("mobile")

        assertEquals(0, changes)
    }
}
