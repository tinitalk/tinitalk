package org.tinitalk.media

internal class NativeResourceOwner {
    private val releases = ArrayDeque<() -> Unit>()
    private var closed = false

    fun own(release: () -> Unit) {
        check(!closed) { "native resource owner is closed" }
        releases.addFirst(release)
    }

    fun close(primaryFailure: Throwable? = null) {
        if (closed) return
        closed = true
        var failure = primaryFailure
        while (releases.isNotEmpty()) {
            try {
                releases.removeFirst().invoke()
            } catch (cleanupFailure: Throwable) {
                if (failure == null) {
                    failure = cleanupFailure
                } else if (cleanupFailure !== failure) {
                    failure.addSuppressed(cleanupFailure)
                }
            }
        }
        if (primaryFailure == null) failure?.let { throw it }
    }
}
