package org.tinitalk.ui.call

import androidx.core.telecom.CallEndpointCompat
import org.tinitalk.telecom.AudioEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioRouteSelectionTest {
    @Test
    fun videoRoutesOnlyEarpieceToSpeaker() {
        val earpiece = AudioEndpoint("earpiece", "Earpiece", CallEndpointCompat.TYPE_EARPIECE)
        val speaker = AudioEndpoint("speaker", "Speaker", CallEndpointCompat.TYPE_SPEAKER)
        val bluetooth = AudioEndpoint("bluetooth", "Bluetooth", CallEndpointCompat.TYPE_BLUETOOTH)
        val wired = AudioEndpoint("wired", "Wired", CallEndpointCompat.TYPE_WIRED_HEADSET)
        val streaming = AudioEndpoint("streaming", "Streaming", CallEndpointCompat.TYPE_STREAMING)
        val available = listOf(earpiece, speaker, bluetooth, wired, streaming)

        assertEquals(speaker, speakerRouteOnCameraPress(true, earpiece, available))
        assertNull(speakerRouteOnCameraPress(false, earpiece, available))
        assertNull(speakerRouteOnCameraPress(true, speaker, available))
        assertNull(speakerRouteOnCameraPress(true, bluetooth, available))
        assertNull(speakerRouteOnCameraPress(true, wired, available))
        assertNull(speakerRouteOnCameraPress(true, streaming, available))
        assertNull(speakerRouteOnCameraPress(true, null, available))
        assertNull(speakerRouteOnCameraPress(true, earpiece, available - speaker))
    }

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
