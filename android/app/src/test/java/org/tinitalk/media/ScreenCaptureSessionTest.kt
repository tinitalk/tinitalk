package org.tinitalk.media

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.view.Surface
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], shadows = [ScreenCaptureSessionTest.Projection::class])
class ScreenCaptureSessionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val projection = Shadow.newInstanceOf(MediaProjection::class.java)
    private val outputs = mutableListOf<Output>()
    private val events = mutableListOf<String>()
    private val session = ScreenCaptureSession(projection, object : MediaProjection.Callback() {}) { w, h ->
        Output(w, h).also { outputs += it }
    }

    @After fun cleanup() { session.close() }

    private inner class Output(val width: Int, val height: Int) : ScreenCaptureOutput {
        val texture = SurfaceTexture(0)
        override val surface = Surface(texture)
        var closed = false
        override fun start() { events += "start ${width}x$height" }
        override fun stop() { events += "stop ${width}x$height" }
        override fun close() {
            assertFalse("output released twice", closed)
            closed = true
            events += "close ${width}x$height"
            surface.release()
            texture.release()
        }
    }

    private fun display() = context.getSystemService(DisplayManager::class.java).displays
        .single { it.name == "TiniTalkScreenCapture" }

    @Test fun rotationReplacesBufferQueueAndStopsOldFramesBeforeStartingNewOnes() {
        session.use {
            it.resize(576, 1280)
            val firstDisplay = display().displayId
            it.resize(1280, 576)
            assertNotSame(outputs[0].surface, outputs[1].surface)
            assertNotSame(outputs[0].texture, outputs[1].texture)
            assertTrue(outputs[0].closed)
            assertFalse(outputs[1].closed)
            assertEquals(listOf("start 576x1280", "stop 576x1280", "start 1280x576", "close 576x1280"), events)
            if (Build.VERSION.SDK_INT >= 31) assertEquals(firstDisplay, display().displayId)
        }
        assertTrue(outputs.all { it.closed })
    }

    @Test fun duplicateResizeDoesNotReplaceOutput() {
        session.use {
            it.resize(576, 1280)
            it.resize(576, 1280)
            assertEquals(1, outputs.size)
        }
    }

    @Test fun repeatedRotationsKeepOnlyCurrentOutputAlive() {
        session.use {
            repeat(8) { index ->
                val (w, h) = if (index % 2 == 0) 576 to 1280 else 1280 to 576
                it.resize(w, h)
                assertEquals(1, outputs.count { output -> !output.closed })
            }
        }
        assertTrue(outputs.all { it.closed })
    }

    @Test fun closeIsIdempotentAndRemovesDisplay() {
        session.resize(576, 1280)
        session.close()
        session.close()
        assertTrue(outputs.single().closed)
        assertFalse(context.getSystemService(DisplayManager::class.java).displays.any { it.name == "TiniTalkScreenCapture" })
    }

    @Test fun failedOutputCreationDoesNotLeakPreviousOutput() {
        session.close()
        var calls = 0
        val freshProjection = Shadow.newInstanceOf(MediaProjection::class.java)
        val failing = ScreenCaptureSession(freshProjection, object : MediaProjection.Callback() {}) { w, h ->
            if (++calls == 2) error("texture allocation failed")
            Output(w, h).also { outputs += it }
        }
        failing.use {
            it.resize(576, 1280)
            assertThrows(IllegalStateException::class.java) { it.resize(1280, 576) }
        }
        assertTrue(outputs.single().closed)
    }

    /** Robolectric has no MediaProjection permission service; retain real shadow DisplayManager behavior. */
    @Implements(MediaProjection::class)
    class Projection {
        private var stopped = false
        private var creates = 0
        @Implementation fun registerCallback(callback: MediaProjection.Callback, handler: Handler?) = Unit
        @Implementation fun unregisterCallback(callback: MediaProjection.Callback) = Unit
        @Implementation fun stop() { stopped = true }
        @Implementation fun createVirtualDisplay(
            name: String, width: Int, height: Int, dpi: Int, flags: Int,
            surface: Surface?, callback: VirtualDisplay.Callback?, handler: Handler?,
        ): VirtualDisplay {
            check(!stopped)
            check(Build.VERSION.SDK_INT < 34 || creates == 0) { "Projection permission reused" }
            creates++
            return RuntimeEnvironment.getApplication().getSystemService(DisplayManager::class.java)
                .createVirtualDisplay(name, width, height, dpi, surface, flags, callback, handler)
        }
    }
}
