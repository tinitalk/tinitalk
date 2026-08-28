package org.tinitalk.ui.call

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
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
    onDragStart: (() -> Unit)? = null,
    onDrag: ((Float, Float) -> Unit)? = null,
    onDragEnd: (() -> Unit)? = null,
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
            onDragStart = onDragStart,
            onDrag = onDrag,
            onDragEnd = onDragEnd,
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
    onDragStart: (() -> Unit)?,
    onDrag: ((Float, Float) -> Unit)?,
    onDragEnd: (() -> Unit)?,
    contentDescription: String?,
    onFrameVisibilityChanged: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val currentVisibilityCallback = rememberUpdatedState(onFrameVisibilityChanged)
    val currentClick = rememberUpdatedState(onClick)
    val currentDragStart = rememberUpdatedState(onDragStart)
    val currentDrag = rememberUpdatedState(onDrag)
    val currentDragEnd = rememberUpdatedState(onDragEnd)
    val touchListener = remember(context) {
        RendererTouchListener(
            context = context,
            onDragStart = { currentDragStart.value?.invoke() },
            onDrag = { x, y -> currentDrag.value?.invoke(x, y) },
            onDragEnd = { currentDragEnd.value?.invoke() },
        )
    }
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
            val clickEnabled = currentClick.value != null
            renderer.setOnClickListener(
                if (clickEnabled) View.OnClickListener { currentClick.value?.invoke() } else null,
            )
            renderer.isClickable = clickEnabled
            val interactive = currentClick.value != null || currentDrag.value != null
            renderer.setOnTouchListener(touchListener.takeIf { interactive })
        },
    )

}

private class RendererTouchListener(
    context: Context,
    private val onDragStart: () -> Unit,
    private val onDrag: (Float, Float) -> Unit,
    private val onDragEnd: () -> Unit,
) : View.OnTouchListener {
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var tracking = false
    private var dragging = false
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f

    override fun onTouch(view: View, event: MotionEvent): Boolean {
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tracking = true
                dragging = false
                downX = event.rawX
                downY = event.rawY
                lastX = downX
                lastY = downY
                true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return false
                val x = event.rawX
                val y = event.rawY
                if (!dragging) {
                    val totalX = x - downX
                    val totalY = y - downY
                    if (totalX * totalX + totalY * totalY > touchSlop * touchSlop) {
                        dragging = true
                        view.parent?.requestDisallowInterceptTouchEvent(true)
                        onDragStart()
                        onDrag(totalX, totalY)
                    }
                } else {
                    onDrag(x - lastX, y - lastY)
                }
                lastX = x
                lastY = y
                true
            }

            MotionEvent.ACTION_UP -> {
                if (!tracking) return false
                if (dragging) onDragEnd() else view.performClick()
                reset()
                true
            }

            MotionEvent.ACTION_CANCEL -> {
                if (tracking && dragging) onDragEnd()
                reset()
                true
            }

            else -> tracking
        }
    }

    private fun reset() {
        tracking = false
        dragging = false
    }
}

private class VideoRendererHandle(
    context: Context,
    private val source: VideoRenderSource,
    private val localOverlay: Boolean,
    private val onVisibilityChanged: (Boolean) -> Unit,
) : AutoCloseable {
    private val stableRemoteRenderer = if (localOverlay) {
        null
    } else {
        StableSurfaceViewRenderer(context, ::onStableSurfaceReady)
    }
    val renderer = stableRemoteRenderer ?: SurfaceViewRenderer(context)
    private var sink: GuardedRendererSink? = null
    private var started = false
    private var stableSurfaceReady = localOverlay
    private var frameVisible = false

    fun start() {
        if (started) return
        started = true
        renderer.setZOrderMediaOverlay(localOverlay)
        renderer.setEnableHardwareScaler(true)
        renderer.visibility = View.INVISIBLE
        renderer.init(source.eglContext, null)
        stableRemoteRenderer?.markInitialized()
        val nextSink = GuardedRendererSink(renderer) { visible ->
            setFrameVisible(visible)
        }
        sink = nextSink
        if (!source.attach(nextSink)) {
            nextSink.close()
            setFrameVisible(false)
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
        stableRemoteRenderer?.markReleased()
        renderer.clearImage()
        renderer.release()
    }

    private fun setFrameVisible(visible: Boolean) {
        frameVisible = visible
        if (visible && !stableSurfaceReady) return
        if (visible) {
            renderer.visibility = View.VISIBLE
        } else {
            renderer.clearImage()
            renderer.visibility = View.INVISIBLE
        }
        onVisibilityChanged(visible)
    }

    private fun onStableSurfaceReady() {
        stableSurfaceReady = true
        if (frameVisible) setFrameVisible(true)
    }
}

/** Keeps adaptive incoming resolutions from repeatedly resizing the full-screen Surface. */
private class StableSurfaceViewRenderer(
    context: Context,
    private val onSurfaceReady: () -> Unit,
) : SurfaceViewRenderer(context) {
    private var initialized = false
    private var ready = false
    private var appliedSize: StableVideoSurfaceSize? = null

    fun markInitialized() {
        initialized = true
        applyStableSize()
    }

    fun markReleased() {
        initialized = false
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        applyStableSize()
    }

    override fun onFrameResolutionChanged(videoWidth: Int, videoHeight: Int, rotation: Int) = Unit

    private fun applyStableSize() {
        if (!initialized) return
        val nextSize = stableVideoSurfaceSize(width, height) ?: return
        if (nextSize == appliedSize) return
        appliedSize = nextSize
        super.onFrameResolutionChanged(nextSize.width, nextSize.height, 0)
        if (!ready) {
            ready = true
            onSurfaceReady()
        }
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
