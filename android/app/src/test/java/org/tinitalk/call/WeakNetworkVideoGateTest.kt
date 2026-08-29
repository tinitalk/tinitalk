package org.tinitalk.call

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeakNetworkVideoGateTest {
    @Test
    fun initialConnectingOnlyMarksTransportUnreadyButReconnectAfterConnectedBlocks() {
        val gate = WeakNetworkVideoGate()
        gate.reset(CurrentCall)

        gate.onTransportUnavailable(CurrentCall, epoch = 1)
        assertFalse(gate.snapshot().transportReady)
        assertFalse(gate.snapshot().networkGated)

        gate.onTransportConnected(CurrentCall, epoch = 1)
        gate.onTransportUnavailable(CurrentCall, epoch = 2)
        assertTrue(gate.snapshot().networkGated)
    }

    @Test
    fun connectedTransportImmediatelyRestoresVideoAfterInterruption() {
        val gate = WeakNetworkVideoGate()
        gate.reset(CurrentCall)
        gate.onTransportConnected(CurrentCall, epoch = 1)
        gate.onTransportUnavailable(CurrentCall, epoch = 2)
        assertTrue(gate.snapshot().networkGated)

        gate.onTransportConnected(CurrentCall, epoch = 2)

        assertFalse(gate.snapshot().networkGated)
    }

    @Test
    fun staleCallAndEpochCannotChangeReplacementState() {
        val gate = WeakNetworkVideoGate()
        gate.reset(CurrentCall)
        gate.onTransportUnavailable(CurrentCall, epoch = 3)
        gate.reset(ReplacementCall)
        assertFalse(gate.snapshot().networkGated)

        gate.onTransportConnected(CurrentCall, epoch = 4)
        gate.onTransportConnected(ReplacementCall, epoch = 2)
        gate.onTransportConnected(ReplacementCall, epoch = 1)
        gate.onTransportUnavailable(ReplacementCall, epoch = 3)
        gate.onTransportConnected(ReplacementCall, epoch = 2)

        val state = gate.snapshot()
        assertTrue(state.networkGated)
        assertFalse(state.transportReady)
    }

    private companion object {
        const val CurrentCall = "call-1"
        const val ReplacementCall = "call-2"
    }
}
