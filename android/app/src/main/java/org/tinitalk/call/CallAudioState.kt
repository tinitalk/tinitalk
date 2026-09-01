package org.tinitalk.call

import org.tinitalk.telecom.AudioEndpointState
import java.util.concurrent.CopyOnWriteArraySet

object CallAudioState {
    private val listeners = CopyOnWriteArraySet<(AudioEndpointState) -> Unit>()

    @Volatile
    private var current = AudioEndpointState()

    fun snapshot(): AudioEndpointState = current

    fun observe(listener: (AudioEndpointState) -> Unit) {
        listeners += listener
        listener(current)
    }

    fun removeObserver(listener: (AudioEndpointState) -> Unit) {
        listeners -= listener
    }

    fun publish(state: AudioEndpointState) {
        current = state
        listeners.forEach { it(state) }
    }

    fun publish(callKey: AccountCallKey, state: AudioEndpointState) {
        publish(state)
        CallUiStateStore.setAudioEndpoints(callKey, state)
    }

    fun reset() {
        publish(AudioEndpointState())
        CallUiStateStore.clearAudioEndpoints()
    }
}
