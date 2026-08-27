package org.tinitalk.media

import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver

open class PeerConnectionObserver(
    private val onLocalIceCandidate: (IceCandidateData) -> Unit = {},
    private val onLocalIceCandidatesRemoved: (List<IceCandidateData>) -> Unit = {},
    private val onConnectionChange: (PeerConnection.IceConnectionState) -> Unit = {},
) : PeerConnection.Observer {
    override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = onConnectionChange(state)
    override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
    override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit

    override fun onIceCandidate(candidate: IceCandidate) {
        onLocalIceCandidate(
            IceCandidateData(
                sdpMid = candidate.sdpMid.orEmpty(),
                sdpMLineIndex = candidate.sdpMLineIndex,
                candidate = candidate.sdp,
            ),
        )
    }

    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {
        onLocalIceCandidatesRemoved(candidates.map { it.toData() })
    }
    override fun onAddStream(stream: MediaStream) = Unit
    override fun onRemoveStream(stream: MediaStream) = Unit
    override fun onDataChannel(channel: DataChannel) = Unit
    override fun onRenegotiationNeeded() = Unit
    override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) = Unit

    private fun IceCandidate.toData() = IceCandidateData(
        sdpMid = sdpMid.orEmpty(),
        sdpMLineIndex = sdpMLineIndex,
        candidate = sdp,
    )
}
