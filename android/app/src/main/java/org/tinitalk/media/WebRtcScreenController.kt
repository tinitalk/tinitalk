package org.tinitalk.media

import android.content.Context
import android.content.Intent
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.Looper
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
    private val firstFrame = AtomicBoolean(false)
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
        if (stopped) return@execute
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
                    if (!success) stop("Не удалось начать показ экрана")
                }
                override fun onCapturerStopped() { videoSource.capturerObserver.onCapturerStopped() }
                override fun onFrameCaptured(frame: VideoFrame) {
                    videoSource.capturerObserver.onFrameCaptured(frame)
                    if (firstFrame.compareAndSet(false, true)) queue.execute {
                        if (!stopped && !started) {
                            started = true
                            onStarted()
                        }
                    }
                }
            })
            val size = displaySize()
            val dimensions = screenCaptureSize(size.x, size.y)
            videoSource.adaptOutputFormat(dimensions.first, dimensions.second, 10)
            val senderLease = SenderTrackLease(
                track = videoTrack,
                attach = { sender.setTrack(it, false) },
                detach = { sender.setTrack(null, false) },
                disable = { it.setEnabled(false) },
                dispose = { resources.close() },
            ).also { lease = it }
            check(senderLease.attach()) { "failed to attach screen track" }
            videoTrack.setEnabled(!paused)
            capture.startCapture(dimensions.first, dimensions.second, 10)
            displayManager.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        } catch (_: Exception) {
            stopOnQueue("Не удалось начать показ экрана")
        }
    }

    fun setPaused(value: Boolean) = queue.execute {
        paused = value
        if (!stopped) track?.setEnabled(!value)
    }

    private fun resize(width: Int, height: Int) = queue.execute {
        if (stopped || width <= 0 || height <= 0) return@execute
        val (w, h) = screenCaptureSize(width, height)
        runCatching {
            source?.adaptOutputFormat(w, h, 10)
            capturer?.changeCaptureFormat(w, h, 10)
        }.onFailure { stopOnQueue("Показ экрана остановлен") }
    }

    fun stop(message: String? = null, afterStopped: () -> Unit = {}) = queue.execute {
        stopOnQueue(message)
        afterStopped()
    }

    private fun stopOnQueue(message: String?) {
        if (stopped) return
        stopped = true
        displayManager.unregisterDisplayListener(displayListener)
        runCatching { track?.setEnabled(false) }
        val released = lease?.release()
        awaitingPeerClose = released?.requiresPeerClosed == true
        runCatching { capturer?.stopCapture() }
        if (!awaitingPeerClose) runCatching { resources.close() }
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
