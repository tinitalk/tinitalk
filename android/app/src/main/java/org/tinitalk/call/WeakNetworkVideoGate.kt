package org.tinitalk.call

internal data class WeakNetworkVideoGateState(
    val callId: String? = null,
    val epoch: Long = -1L,
    val transportReady: Boolean = false,
    val networkGated: Boolean = false,
)

/** Keeps camera capture paused only while an established call has no media transport. */
internal class WeakNetworkVideoGate {
    private var state = WeakNetworkVideoGateState()
    private var everConnected = false

    fun snapshot(): WeakNetworkVideoGateState = state

    fun reset(callId: String?) {
        state = WeakNetworkVideoGateState(callId = callId)
        everConnected = false
    }

    fun onTransportUnavailable(callId: String, epoch: Long): WeakNetworkVideoGateState {
        if (!accepts(callId, epoch)) return state
        state = state.copy(
            epoch = epoch,
            transportReady = false,
            networkGated = state.networkGated || everConnected,
        )
        return state
    }

    fun onTransportConnected(callId: String, epoch: Long): WeakNetworkVideoGateState {
        if (!accepts(callId, epoch)) return state
        everConnected = true
        state = state.copy(epoch = epoch, transportReady = true, networkGated = false)
        return state
    }

    private fun accepts(callId: String, epoch: Long): Boolean =
        state.callId == callId && epoch >= state.epoch
}
