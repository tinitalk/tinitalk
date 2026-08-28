package org.tinitalk.call

enum class NetworkQuality {
    Good,
    Poor,
}

internal data class WeakNetworkVideoGateState(
    val callId: String? = null,
    val epoch: Long = -1L,
    val transportReady: Boolean = false,
    val networkGated: Boolean = false,
)

/** Pure call-scoped hysteresis. Transport and stats epochs are supplied by the service. */
internal class WeakNetworkVideoGate {
    private var state = WeakNetworkVideoGateState()
    private var poorSamples = 0
    private var goodSamples = 0
    private var everConnected = false

    fun snapshot(): WeakNetworkVideoGateState = state

    fun reset(callId: String?) {
        state = WeakNetworkVideoGateState(callId = callId)
        poorSamples = 0
        goodSamples = 0
        everConnected = false
    }

    fun onTransportUnavailable(callId: String, epoch: Long): WeakNetworkVideoGateState {
        if (!accepts(callId, epoch)) return state
        if (epoch > state.epoch) clearSamples()
        state = state.copy(
            epoch = epoch,
            transportReady = false,
            networkGated = state.networkGated || everConnected,
        )
        clearSamples()
        return state
    }

    fun onTransportConnected(callId: String, epoch: Long): WeakNetworkVideoGateState {
        if (!accepts(callId, epoch)) return state
        if (epoch > state.epoch) {
            clearSamples()
            state = state.copy(epoch = epoch)
        }
        everConnected = true
        state = state.copy(transportReady = true)
        return state
    }

    fun onQualitySample(
        callId: String,
        epoch: Long,
        quality: NetworkQuality,
    ): WeakNetworkVideoGateState {
        if (state.callId != callId || state.epoch != epoch || !state.transportReady) return state
        when (quality) {
            NetworkQuality.Poor -> {
                poorSamples++
                goodSamples = 0
                if (poorSamples >= PoorSamplesRequired) {
                    state = state.copy(networkGated = true)
                }
            }
            NetworkQuality.Good -> {
                poorSamples = 0
                if (state.networkGated) {
                    goodSamples++
                    if (goodSamples >= GoodSamplesRequired) {
                        goodSamples = 0
                        state = state.copy(networkGated = false)
                    }
                } else {
                    goodSamples = 0
                }
            }
        }
        return state
    }

    private fun accepts(callId: String, epoch: Long): Boolean =
        state.callId == callId && epoch >= state.epoch

    private fun clearSamples() {
        poorSamples = 0
        goodSamples = 0
    }

    private companion object {
        const val PoorSamplesRequired = 3
        const val GoodSamplesRequired = 2
    }
}
