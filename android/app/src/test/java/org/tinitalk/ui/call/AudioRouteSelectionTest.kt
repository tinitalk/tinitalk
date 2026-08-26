package org.tinitalk.ui.call

import androidx.core.telecom.CallEndpointCompat
import org.tinitalk.telecom.AudioEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioRouteSelectionTest {
    @Test
    fun togglesDirectlyOnlyBetweenEarpieceAndSpeaker() {
        val earpiece = AudioEndpoint("earpiece", "Earpiece", CallEndpointCompat.TYPE_EARPIECE)
        val speaker = AudioEndpoint("speaker", "Speaker", CallEndpointCompat.TYPE_SPEAKER)
        val bluetooth = AudioEndpoint("bluetooth", "Headset", CallEndpointCompat.TYPE_BLUETOOTH)
        val phoneRoutes = listOf(earpiece, speaker)

        assertEquals(speaker, directAudioRoute(earpiece, phoneRoutes))
        assertEquals(earpiece, directAudioRoute(speaker, phoneRoutes))
        assertEquals(speaker, directAudioRoute(null, phoneRoutes))
        assertNull(directAudioRoute(earpiece, phoneRoutes + bluetooth))
    }
}
