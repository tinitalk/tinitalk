package org.tinitalk.media

import android.content.Context
import android.content.Intent
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.WindowManager
import org.webrtc.CapturerObserver
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpSender
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoFrame
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import kotlin.math.roundToInt
import java.util.concurrent.atomic.AtomicBoolean

/** All native ownership changes run on the media control queue, never the UI thread. */
internal class WebRtcScreenController(
    private val context: Context,
    private val factory: PeerConnectionFactory,
    private val egl: EglBase.Context,
    private val sender: RtpSender,
    private val queue: CameraTaskQueue,
    private val onStarted: () -> Unit,
    private val onStopped: (String?) -> Unit,
) {
    private val resources = NativeResourceOwner()
    private var capturer: ScreenCapturerAndroid? = null
    private var source: VideoSource? = null
    private var track: VideoTrack? = null
    private var lease: SenderTrackLease<VideoTrack>? = null
    private var stopped = false
    private var started = false
    private var paused = false
    private var captureSize = 0 to 0
    private val firstFrame = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)
    private var awaitingPeerClose = false
    val requiresPeerClose: Boolean get() = awaitingPeerClose
    private val displayManager = context.getSystemService(DisplayManager::class.java)
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (Build.VERSION.SDK_INT < 34 && displayId == Display.DEFAULT_DISPLAY) {
                val size = displaySize()
                resize(size.x, size.y)
            }
        }
    }

    fun start(permission: Intent) = queue.execute {
        if (stopped || stopRequested.get()) return@execute
        try {
            val helper = SurfaceTextureHelper.create("TiniTalkScreenCapture", egl)
            resources.own(helper::dispose)
            val videoSource = factory.createVideoSource(true).also { source = it }
            resources.own(videoSource::dispose)
            val videoTrack = factory.createVideoTrack("screen", videoSource).also { track = it }
            resources.own(videoTrack::dispose)
            val capture = ScreenCapturerAndroid(permission, object : MediaProjection.Callback() {
                override fun onStop() { stop() }
                override fun onCapturedContentResize(width: Int, height: Int) { resize(width, height) }
            }).also { capturer = it }
            resources.own(capture::dispose)
            capture.initialize(helper, context, object : CapturerObserver {
                override fun onCapturerStarted(success: Boolean) {
                    videoSource.capturerObserver.onCapturerStarted(success)
                    if (!success) {
                        Log.e("TiniTalkScreen", "capturer reported start failure")
                        stop("Не удалось начать показ экрана")
                    }
                }
                override fun onCapturerStopped() { videoSource.capturerObserver.onCapturerStopped() }
                override fun onFrameCaptured(frame: VideoFrame) {
                    videoSource.capturerObserver.onFrameCaptured(frame)
                    if (firstFrame.compareAndSet(false, true)) queue.execute {
                        if (!stopped && !stopRequested.get() && !started) {
                            started = true
                            onStarted()
                        }
                    }
                }
            })
            val size = displaySize()
            val dimensions = screenCaptureSize(size.x, size.y)
            captureSize = dimensions
            videoSource.adaptOutputFormat(dimensions.first, dimensions.second, WebRtcPolicy.screenCaptureFps)
            val senderLease = SenderTrackLease(
                track = videoTrack,
                attach = { sender.setTrack(it, false) },
                detach = { sender.setTrack(null, false) },
                disable = { it.setEnabled(false) },
                dispose = { resources.close() },
            ).also { lease = it }
            check(senderLease.attach()) { "failed to attach screen track" }
            videoTrack.setEnabled(!paused)
            capture.startCapture(dimensions.first, dimensions.second, WebRtcPolicy.screenCaptureFps)
            displayManager.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        } catch (failure: Exception) {
            Log.e("TiniTalkScreen", "capture start failed", failure)
            stopOnQueue("Не удалось начать показ экрана")
        }
    }

    fun setPaused(value: Boolean) = queue.execute {
        val recovering = paused && !value
        paused = value
        if (!stopped) track?.setEnabled(!value)
        if (recovering) refreshSenderOnQueue()
    }

    fun refreshSender() = queue.execute { refreshSenderOnQueue() }

    private fun refreshSenderOnQueue() {
        if (!stopped && started && !paused && lease?.refresh() != true) {
            stopOnQueue("Не удалось восстановить показ экрана. Включите его ещё раз.")
        }
    }

    private fun resize(width: Int, height: Int) = queue.execute {
        if (stopped || stopRequested.get() || width <= 0 || height <= 0) return@execute
        val dimensions = screenCaptureSize(width, height)
        // Resizing can trigger another size callback, even with identical dimensions.
        if (dimensions == captureSize) return@execute
        captureSize = dimensions
        val (w, h) = dimensions
        runCatching {
            source?.adaptOutputFormat(w, h, WebRtcPolicy.screenCaptureFps)
            capturer?.changeCaptureFormat(w, h, WebRtcPolicy.screenCaptureFps)
        }.onFailure {
            Log.e("TiniTalkScreen", "capture resize failed: ${w}x$h", it)
            stopOnQueue("Показ экрана остановлен")
        }
    }

    fun stop(message: String? = null, afterStopped: () -> Unit = {}) {
        stopRequested.set(true)
        queue.execute {
            stopOnQueue(message)
            afterStopped()
        }
    }

    private fun stopOnQueue(message: String?) {
        if (stopped) return
        stopped = true
        displayManager.unregisterDisplayListener(displayListener)
        runCatching { track?.setEnabled(false) }
        val released = lease?.release()
        awaitingPeerClose = released?.requiresPeerClosed == true
        runCatching { capturer?.stopCapture() }
            .onFailure { Log.e("TiniTalkScreen", "capture stop failed", it) }
        if (!awaitingPeerClose) runCatching { resources.close() }
            .onFailure { Log.e("TiniTalkScreen", "capture cleanup failed", it) }
        onStopped(message ?: released?.failure?.let { "Показ экрана остановлен" })
    }

    // Called on the media control queue after the peer has released its sender.
    fun disposeAfterPeerClosed() {
        if (awaitingPeerClose) runCatching { resources.close() }
    }

    @Suppress("DEPRECATION")
    private fun displaySize(): Point {
        val windowManager = context.getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= 30) windowManager.maximumWindowMetrics.bounds.let { Point(it.width(), it.height()) }
        else Point().also { windowManager.defaultDisplay.getRealSize(it) }
    }
}

internal fun screenCaptureSize(width: Int, height: Int): Pair<Int, Int> {
    val scale = minOf(1.0, 1280.0 / maxOf(width, height).coerceAtLeast(1))
    fun even(value: Int) = ((value * scale).roundToInt() / 2 * 2).coerceAtLeast(2)
    return even(width) to even(height)
}
