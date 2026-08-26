package org.tinitalk.telecom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioEndpointStateCodecTest {
    @Test
    fun roundTripsAudioEndpoints() {
        val expected = AudioEndpointState(
            current = AudioEndpoint("speaker-id", "Speaker", 4),
            available = listOf(
                AudioEndpoint("speaker-id", "Speaker", 4),
                AudioEndpoint("earpiece-id", "Earpiece", 1),
            ),
        )

        assertEquals(expected, AudioEndpointStateCodec.decode(AudioEndpointStateCodec.encode(expected)))
    }

    @Test
    fun boundsEndpointCountAndFieldLengths() {
        val longId = "i".repeat(AudioEndpointStateCodec.MaxFieldLength + 1)
        val longName = "n".repeat(AudioEndpointStateCodec.MaxFieldLength + 1)
        val state = AudioEndpointState(
            current = AudioEndpoint(longId, longName, 4),
            available = (0..AudioEndpointStateCodec.MaxEndpointCount).map { AudioEndpoint("$longId$it", longName, it) },
        )

        val decoded = requireNotNull(AudioEndpointStateCodec.decode(AudioEndpointStateCodec.encode(state)))

        assertEquals(AudioEndpointStateCodec.MaxEndpointCount, decoded.available.size)
        assertEquals(AudioEndpointStateCodec.MaxFieldLength, requireNotNull(decoded.current).id.length)
        assertEquals(AudioEndpointStateCodec.MaxFieldLength, decoded.current.name.length)
        assertEquals(AudioEndpointStateCodec.MaxFieldLength, decoded.available.first().id.length)
    }

    @Test
    fun rejectsMissingOrMalformedPayload() {
        assertNull(AudioEndpointStateCodec.decode(null))
        assertNull(AudioEndpointStateCodec.decode("not-json"))
        assertNull(AudioEndpointStateCodec.decode("""{"available":[{"id":"speaker"}]}"""))
    }
}
