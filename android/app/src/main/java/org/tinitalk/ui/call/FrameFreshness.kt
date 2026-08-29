package org.tinitalk.ui.call

internal enum class FrameVisibilityChange {
    None,
    BecameVisible,
    BecameHidden,
}

/** Thread-safe frame timestamp state. It never retains a native frame. */
internal class FrameFreshness(
    private val timeoutMillis: Long,
) {
    private var closed = false
    private var lastFrameMillis: Long? = null

    @Volatile
    var visible: Boolean = false
        private set

    @Synchronized
    fun onFrame(nowMillis: Long): FrameVisibilityChange {
        if (closed) return FrameVisibilityChange.None
        lastFrameMillis = nowMillis
        if (visible) return FrameVisibilityChange.None
        visible = true
        return FrameVisibilityChange.BecameVisible
    }

    @Synchronized
    fun onTimeout(nowMillis: Long): FrameVisibilityChange {
        val lastFrame = lastFrameMillis
        if (closed || !visible || lastFrame == null || nowMillis - lastFrame < timeoutMillis) {
            return FrameVisibilityChange.None
        }
        visible = false
        return FrameVisibilityChange.BecameHidden
    }

    @Synchronized
    fun remainingMillis(nowMillis: Long): Long? {
        val lastFrame = lastFrameMillis ?: return null
        if (closed || !visible) return null
        return (timeoutMillis - (nowMillis - lastFrame)).coerceAtLeast(0L)
    }

    @Synchronized
    fun close() {
        closed = true
        visible = false
        lastFrameMillis = null
    }
}
