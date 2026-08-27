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

    fun remove(candidates: List<IceCandidateData>): List<IceCandidateData> {
        val buffered = candidates.filterTo(mutableSetOf()) { it in pending }
        pending.removeAll(buffered)
        return candidates.filterNot { it in buffered }
    }

    fun clear() {
        pending.clear()
    }
}
