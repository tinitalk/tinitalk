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
    fun threePoorSamplesBlockAndTwoGoodSamplesRecover() {
        val gate = WeakNetworkVideoGate()
        gate.reset(CurrentCall)
        gate.onTransportConnected(CurrentCall, epoch = 1)

        gate.onQualitySample(CurrentCall, epoch = 1, NetworkQuality.Poor)
        gate.onQualitySample(CurrentCall, epoch = 1, NetworkQuality.Poor)
        assertFalse(gate.snapshot().networkGated)

        gate.onQualitySample(CurrentCall, epoch = 1, NetworkQuality.Poor)
        assertTrue(gate.snapshot().networkGated)

        gate.onQualitySample(CurrentCall, epoch = 1, NetworkQuality.Good)
        assertTrue(gate.snapshot().networkGated)
        gate.onQualitySample(CurrentCall, epoch = 1, NetworkQuality.Good)
        assertFalse(gate.snapshot().networkGated)
    }

    @Test
    fun oppositeAndAlternatingSamplesResetTheConsecutiveCounter() {
        val gate = WeakNetworkVideoGate()
        gate.reset(CurrentCall)
        gate.onTransportConnected(CurrentCall, epoch = 1)

        repeat(4) {
            gate.onQualitySample(CurrentCall, epoch = 1, NetworkQuality.Poor)
            gate.onQualitySample(CurrentCall, epoch = 1, NetworkQuality.Good)
        }

        assertFalse(gate.snapshot().networkGated)
    }

    @Test
    fun reconnectBlocksImmediatelyAndDuplicateConnectedKeepsRecoveryProgress() {
        val gate = WeakNetworkVideoGate()
        gate.reset(CurrentCall)
        gate.onTransportConnected(CurrentCall, epoch = 1)

        gate.onTransportUnavailable(CurrentCall, epoch = 2)
        assertTrue(gate.snapshot().networkGated)
        assertFalse(gate.snapshot().transportReady)

        gate.onTransportConnected(CurrentCall, epoch = 2)
        gate.onQualitySample(CurrentCall, epoch = 2, NetworkQuality.Good)
        gate.onTransportConnected(CurrentCall, epoch = 2)
        gate.onQualitySample(CurrentCall, epoch = 2, NetworkQuality.Good)

        assertFalse(gate.snapshot().networkGated)
        assertTrue(gate.snapshot().transportReady)
    }

    @Test
    fun staleCallEpochAndSamplesWhileDisconnectedCannotChangeReplacementState() {
        val gate = WeakNetworkVideoGate()
        gate.reset(CurrentCall)
        gate.onTransportUnavailable(CurrentCall, epoch = 3)
        gate.reset(ReplacementCall)
        assertFalse(gate.snapshot().networkGated)

        gate.onTransportConnected(CurrentCall, epoch = 4)
        gate.onTransportConnected(ReplacementCall, epoch = 2)
        gate.onQualitySample(ReplacementCall, epoch = 1, NetworkQuality.Poor)
        gate.onTransportUnavailable(ReplacementCall, epoch = 3)
        gate.onQualitySample(ReplacementCall, epoch = 3, NetworkQuality.Good)
        gate.onQualitySample(ReplacementCall, epoch = 3, NetworkQuality.Good)

        val state = gate.snapshot()
        assertTrue(state.networkGated)
        assertFalse(state.transportReady)
    }

    private companion object {
        const val CurrentCall = "call-1"
        const val ReplacementCall = "call-2"
    }
}
