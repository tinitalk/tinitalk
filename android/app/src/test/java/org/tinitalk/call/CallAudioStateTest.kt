package org.tinitalk.call

import org.tinitalk.telecom.AudioEndpoint
import org.tinitalk.telecom.AudioEndpointState
import org.junit.Assert.assertEquals
import org.junit.Test

class CallAudioStateTest {
    @Test
    fun publishesRoutesToObserverAndClearsThemOnReset() {
        val observed = mutableListOf<AudioEndpointState>()
        val listener: (AudioEndpointState) -> Unit = { observed += it }
        val routes = AudioEndpointState(
            current = AudioEndpoint("speaker-id", "Speaker", 4),
            available = listOf(AudioEndpoint("speaker-id", "Speaker", 4)),
        )

        CallAudioState.observe(listener)
        CallAudioState.publish(routes)
        CallAudioState.reset()
        CallAudioState.removeObserver(listener)

        assertEquals(routes, observed[1])
        assertEquals(AudioEndpointState(), observed.last())
    }
}
