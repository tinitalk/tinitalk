package org.tinitalk.media

import org.tinitalk.call.CameraFacing
import java.time.Instant

data class IceCandidateData(
    val sdpMid: String,
    val sdpMLineIndex: Int,
    val candidate: String,
)

data class IceServerData(
    val urls: List<String>,
    val username: String = "",
    val password: String = "",
    val expiresAt: Instant? = null,
)

enum class MediaConnectionState {
    Connecting,
    Connected,
    Disconnected,
    Failed,
    Closed,
}

interface MediaSession {
    suspend fun createOffer(): String
    suspend fun acceptOffer(sdp: String): String
    suspend fun setAnswer(sdp: String)
    suspend fun addIceCandidate(candidate: IceCandidateData)
    suspend fun removeIceCandidates(candidates: List<IceCandidateData>)
    suspend fun restartIce(): String
    suspend fun updateIceServers(servers: List<IceServerData>)
    fun beginRemoteDescription()
    fun onNetworkChanged()
    fun setMuted(muted: Boolean)
    fun setActive(active: Boolean)
    fun getStats(onResult: (CallStats) -> Unit)
    suspend fun close()
}

interface CameraMediaSession {
    fun startCamera()
    /** Rebinds an active video track after the media transport moves to a new network path. */
    fun refreshVideoSender(onFailure: () -> Unit = {}) = Unit
    /** [onDetached] is logical/nonblocking; [onReleased] follows native stop and safe disposal. */
    fun pauseCamera(
        onDetached: () -> Unit = {},
        onReleased: () -> Unit = {},
    )
    /** [onDetached] is logical/nonblocking; [onReleased] follows native stop and safe disposal. */
    fun stopCamera(
        onDetached: () -> Unit = {},
        onReleased: () -> Unit = {},
    )
    fun switchCamera()
}

data class CameraMediaCallbacks(
    val onLocalTrackChanged: (VideoRenderSource?) -> Unit = {},
    val onCaptureStarted: (CameraFacing) -> Unit = {},
    val onCaptureInvalidated: () -> Unit = {},
    val onCaptureStopped: () -> Unit = {},
    val onFacingChanged: (CameraFacing) -> Unit = {},
    val onFailure: (String) -> Unit = {},
)
