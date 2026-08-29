package org.tinitalk.media

internal class SenderTrackLease<Track>(
    private val track: Track,
    private val attach: (Track) -> Boolean,
    private val detach: () -> Boolean,
    private val disable: (Track) -> Unit,
    private val dispose: (Track) -> Unit,
) {
    private var attached = false
    private var released = false
    private var disposed = false

    fun attach(): Boolean {
        check(!released)
        attached = attach(track)
        if (!attached) {
            released = true
            dispose()
        }
        return attached
    }

    fun refresh(): Boolean {
        if (released || !attached) return false
        val detached = runCatching(detach).getOrDefault(false)
        if (!detached) return false
        attached = false
        attached = runCatching { attach(track) }.getOrDefault(false)
        return attached
    }

    fun release(): CameraAttemptRelease {
        if (released) return CameraAttemptRelease()
        released = true
        val detached = !attached || runCatching(detach).getOrDefault(false)
        if (detached) {
            return CameraAttemptRelease()
        }
        runCatching { disable(track) }
        return CameraAttemptRelease(
            failure = "failed to detach local video track",
            requiresPeerClosed = true,
        )
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        dispose(track)
    }
}
