package org.tinitalk.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.webrtc.IceCandidate
import org.webrtc.VideoTrack

class PeerConnectionObserverTest {
    @Test
    fun reportsRemovedIceCandidatesAsData() {
        var removed = emptyList<IceCandidateData>()
        val observer = PeerConnectionObserver(onLocalIceCandidatesRemoved = { removed = it })

        observer.onIceCandidatesRemoved(
            arrayOf(
                IceCandidate("audio", 0, "candidate:first"),
                IceCandidate(null, 1, "candidate:second"),
            ),
        )

        assertEquals(
            listOf(
                IceCandidateData("audio", 0, "candidate:first"),
                IceCandidateData("", 1, "candidate:second"),
            ),
            removed,
        )
    }

    @Test
    fun reportsRemoteVideoTrackForRendering() {
        var reported: VideoTrack? = null
        val track = VideoTrack(1L)
        val observer = PeerConnectionObserver(onRemoteVideoTrack = { reported = it })

        observer.onRemoteTrack(track)

        assertSame(track, reported)
    }
}
