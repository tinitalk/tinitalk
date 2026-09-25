package org.tinitalk.media

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Surface
import org.webrtc.EglBase
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSink

/** Owned by the media control queue. Only frame delivery runs on the texture thread. */
internal class ScreenCaptureSession(
    private val projection: MediaProjection,
    private val callback: MediaProjection.Callback,
    private val createOutput: (Int, Int) -> ScreenCaptureOutput,
) : AutoCloseable {
    private var display: VirtualDisplay? = null
    private var output: ScreenCaptureOutput? = null
    private var size: Pair<Int, Int>? = null
    private var closed = false

    init {
        // This handler must outlive the individual capture textures replaced on rotation.
        try {
            projection.registerCallback(callback, Handler(Looper.getMainLooper()))
        } catch (failure: Exception) {
            runCatching { projection.stop() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    fun resize(width: Int, height: Int) {
        check(!closed)
        require(width > 0 && height > 0)
        if (size == (width to height)) return
        val next = createOutput(width, height)
        val previous = output
        output = next // close() also owns the new output if display reconfiguration fails.
        try {
            previous?.stop()
            val currentDisplay = display
            if (currentDisplay == null || Build.VERSION.SDK_INT < 31) {
                currentDisplay?.release()
                display = null
                display = checkNotNull(projection.createVirtualDisplay(
                    "TiniTalkScreenCapture", width, height, 400,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION,
                    next.surface, null, null,
                ))
            } else {
                // A new Surface wrapper around the SAME SurfaceTexture is not enough: some
                // devices retain the old compositor output geometry after VirtualDisplay.resize.
                // Replace the buffer queue too, while keeping the projection/display (Android 14+
                // permits only one createVirtualDisplay call per permission grant).
                currentDisplay.resize(width, height, 400)
                currentDisplay.surface = next.surface
            }
            next.start()
            size = width to height
        } finally {
            previous?.close()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        val cleanup = NativeResourceOwner()
        cleanup.own(projection::stop)
        output?.let { cleanup.own(it::close) }
        display?.let { cleanup.own(it::release) }
        cleanup.own { projection.unregisterCallback(callback) }
        output = null
        display = null
        cleanup.close()
    }
}

internal interface ScreenCaptureOutput : AutoCloseable {
    val surface: Surface
    fun start()
    fun stop()
}

internal fun screenCaptureOutput(
    width: Int,
    height: Int,
    egl: EglBase.Context,
    sink: VideoSink,
): ScreenCaptureOutput {
    val helper = checkNotNull(SurfaceTextureHelper.create("TiniTalkScreenCapture", egl))
    val resources = NativeResourceOwner().apply { own(helper::dispose) }
    try {
        helper.setTextureSize(width, height)
        val surface = Surface(helper.surfaceTexture)
        resources.own(surface::release)
        resources.own(helper::stopListening)
        return object : ScreenCaptureOutput {
            override val surface = surface
            override fun start() = helper.startListening(sink)
            override fun stop() = helper.stopListening()
            // SurfaceTextureHelper defers its final GL release until outstanding frames return.
            override fun close() = resources.close()
        }
    } catch (failure: Exception) {
        resources.close(failure)
        throw failure
    }
}
