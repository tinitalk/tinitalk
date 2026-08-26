package org.tinitalk.media

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

interface MediaSession {
    suspend fun createOffer(): String
    suspend fun acceptOffer(sdp: String): String
    suspend fun setAnswer(sdp: String)
    suspend fun addIceCandidate(candidate: IceCandidateData)
    suspend fun restartIce(): String
    suspend fun updateIceServers(servers: List<IceServerData>)
    fun setMuted(muted: Boolean)
    fun setActive(active: Boolean)
    suspend fun close()
}
