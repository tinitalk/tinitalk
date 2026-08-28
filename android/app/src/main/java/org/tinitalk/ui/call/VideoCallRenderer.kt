package org.tinitalk.ui.call

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.tinitalk.media.VideoRenderSource
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink

@Composable
internal fun VideoCallRenderer(
    source: VideoRenderSource,
    mirror: Boolean,
    localOverlay: Boolean,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentDescription: String? = null,
    onFrameVisibilityChanged: (Boolean) -> Unit,
) {
    androidx.compose.runtime.key(source) {
        VideoCallRendererForSource(
            source = source,
            mirror = mirror,
            localOverlay = localOverlay,
            modifier = modifier,
            onClick = onClick,
            contentDescription = contentDescription,
            onFrameVisibilityChanged = onFrameVisibilityChanged,
        )
    }
}

@Composable
private fun VideoCallRendererForSource(
    source: VideoRenderSource,
    mirror: Boolean,
    localOverlay: Boolean,
    modifier: Modifier,
    onClick: (() -> Unit)?,
    contentDescription: String?,
    onFrameVisibilityChanged: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val currentVisibilityCallback = rememberUpdatedState(onFrameVisibilityChanged)
    val currentClick = rememberUpdatedState(onClick)
    val handle = remember {
        VideoRendererHandle(
            context = context,
            source = source,
            localOverlay = localOverlay,
            onVisibilityChanged = { currentVisibilityCallback.value(it) },
        )
    }

    DisposableEffect(handle) {
        onDispose { handle.close() }
    }

    AndroidView(
        factory = { handle.renderer.also { handle.start() } },
        modifier = modifier,
        update = { renderer ->
            renderer.setMirror(mirror)
            renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            renderer.contentDescription = contentDescription
            renderer.isClickable = currentClick.value != null
            renderer.setOnClickListener { currentClick.value?.invoke() }
        },
    )

}

private class VideoRendererHandle(
    context: Context,
    private val source: VideoRenderSource,
    private val localOverlay: Boolean,
    private val onVisibilityChanged: (Boolean) -> Unit,
) : AutoCloseable {
    val renderer = SurfaceViewRenderer(context)
    private var sink: GuardedRendererSink? = null
    private var started = false

    fun start() {
        if (started) return
        started = true
        renderer.setZOrderMediaOverlay(localOverlay)
        renderer.setEnableHardwareScaler(true)
        renderer.visibility = View.INVISIBLE
        renderer.init(source.eglContext, null)
        val nextSink = GuardedRendererSink(renderer) { visible ->
            if (visible) {
                renderer.visibility = View.VISIBLE
            } else {
                renderer.clearImage()
                renderer.visibility = View.INVISIBLE
            }
            onVisibilityChanged(visible)
        }
        sink = nextSink
        if (!source.attach(nextSink)) {
            nextSink.close()
            renderer.clearImage()
            renderer.visibility = View.INVISIBLE
            onVisibilityChanged(false)
        }
    }

    override fun close() {
        if (!started) return
        started = false
        val attachedSink = sink
        sink = null
        if (attachedSink != null) {
            source.detach(attachedSink)
            attachedSink.close()
        }
        renderer.clearImage()
        renderer.release()
    }
}

/** Prevents an in-flight decoder callback from reaching a renderer after teardown begins. */
private class GuardedRendererSink(
    private val renderer: VideoSink,
    onVisibilityChanged: (Boolean) -> Unit,
) : VideoSink, AutoCloseable {
    private val watchdog = FrameVisibilityWatchdog(onVisibilityChanged = onVisibilityChanged)
    private var open = true

    override fun onFrame(frame: VideoFrame) {
        synchronized(this) {
            if (!open) return
            watchdog.onFrame()
            renderer.onFrame(frame)
        }
    }

    override fun close() {
        synchronized(this) {
            if (!open) return
            open = false
        }
        watchdog.close()
    }
}

private class FrameVisibilityWatchdog(
    private val timeoutMillis: Long = RemoteFrameTimeoutMillis,
    private val handler: Handler = Handler(Looper.getMainLooper()),
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val onVisibilityChanged: (Boolean) -> Unit,
) : AutoCloseable {
    private val freshness = FrameFreshness(timeoutMillis)
    private var reportedVisible = false
    private val timeout = object : Runnable {
        override fun run() {
            when (freshness.onTimeout(clock())) {
                FrameVisibilityChange.BecameHidden -> report(false)
                FrameVisibilityChange.None -> armTimeout()
                FrameVisibilityChange.BecameVisible -> Unit
            }
        }
    }

    fun onFrame() {
        if (freshness.onFrame(clock()) != FrameVisibilityChange.BecameVisible) return
        handler.post {
            if (!freshness.visible) return@post
            report(true)
            armTimeout()
        }
    }

    override fun close() {
        freshness.close()
        handler.removeCallbacks(timeout)
        if (Looper.myLooper() == Looper.getMainLooper()) {
            report(false)
        } else {
            handler.post { report(false) }
        }
    }

    private fun armTimeout() {
        handler.removeCallbacks(timeout)
        val remaining = freshness.remainingMillis(clock()) ?: return
        handler.postDelayed(timeout, remaining.coerceAtLeast(1L))
    }

    private fun report(visible: Boolean) {
        if (reportedVisible == visible) return
        reportedVisible = visible
        onVisibilityChanged(visible)
    }
}

private const val RemoteFrameTimeoutMillis = 1_500L
