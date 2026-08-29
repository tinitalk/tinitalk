package org.tinitalk.media

import org.webrtc.EglBase
import org.webrtc.VideoSink
import org.webrtc.VideoTrack

internal fun shouldExposeRemoteVideo(
    videoAllowed: Boolean,
    eglAvailable: Boolean,
    sessionClosed: Boolean,
): Boolean = videoAllowed && eglAvailable && !sessionClosed

/**
 * The only rendering boundary exposed outside the WebRTC session. It owns sink attachment, but it
 * deliberately does not own the native track or the shared EGL context.
 */
class VideoRenderSource internal constructor(
    track: VideoTrack,
    val eglContext: EglBase.Context,
) : AutoCloseable {
    private val sinks = VideoSinkRegistry<VideoSink>(track::addSink, track::removeSink)

    fun attach(sink: VideoSink): Boolean = sinks.attach(sink)

    fun detach(sink: VideoSink) = sinks.detach(sink)

    override fun close() = sinks.close()
}

/** Serializes attach against close so a renderer can never be left on a retiring native track. */
internal class VideoSinkRegistry<Sink>(
    private val attachNative: (Sink) -> Unit,
    private val detachNative: (Sink) -> Unit,
) : AutoCloseable {
    private val sinks = LinkedHashSet<Sink>()
    private var closed = false

    @Synchronized
    fun attach(sink: Sink): Boolean {
        if (closed) return false
        if (!sinks.add(sink)) return true
        return try {
            attachNative(sink)
            true
        } catch (_: Throwable) {
            sinks.remove(sink)
            runCatching { detachNative(sink) }
            false
        }
    }

    @Synchronized
    fun detach(sink: Sink) {
        if (sinks.remove(sink)) runCatching { detachNative(sink) }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        val attached = sinks.toList()
        sinks.clear()
        attached.forEach { sink -> runCatching { detachNative(sink) } }
    }
}
