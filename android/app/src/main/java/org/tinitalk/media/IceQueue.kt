package org.tinitalk.media

class IceQueue {
    private val pending = mutableListOf<IceCandidateData>()
    private var remoteDescriptionReady = false

    fun addOrBuffer(candidate: IceCandidateData): List<IceCandidateData> {
        if (remoteDescriptionReady) return listOf(candidate)
        pending += candidate
        return emptyList()
    }

    fun beginRemoteDescription() {
        remoteDescriptionReady = false
    }

    fun markRemoteDescriptionReady(): List<IceCandidateData> {
        remoteDescriptionReady = true
        val ready = pending.toList()
        pending.clear()
        return ready
    }

    fun clear() {
        pending.clear()
    }
}
