package org.tinitalk.ui.call

import android.os.Looper
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.webrtc.VideoFrame
import org.webrtc.VideoSink

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GuardedRendererSinkTest {
    @Test
    fun sharedScreenReportsFullRotatedDimensionsAndKeepsStaticFrame() {
        val sizes = mutableListOf<Pair<Int, Int>>()
        val visibility = mutableListOf<Boolean>()
        val rendered = mutableListOf<VideoFrame>()
        val sink = GuardedRendererSink(VideoSink { rendered.add(it) }, visibility::add,
            { width, height -> sizes.add(width to height) }, keepLastFrame = true)
        val desktop = frame(2520, 1680)
        val portrait = frame(1920, 1080, rotation = 90)

        sink.onFrame(desktop)
        shadowOf(Looper.getMainLooper()).idle()
        sink.onFrame(portrait)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))

        assertEquals(listOf(2520 to 1680, 1080 to 1920), sizes)
        assertEquals(listOf(desktop, portrait), rendered)
        assertEquals(listOf(true), visibility)
        sink.close()
        assertEquals(listOf(true, false), visibility)
    }

    @Test
    fun disposingRendererCancelsPendingResizeAndRejectsLateFrames() {
        val sizes = mutableListOf<Pair<Int, Int>>()
        val visibility = mutableListOf<Boolean>()
        var rendered = 0
        val sink = GuardedRendererSink(VideoSink { rendered++ }, visibility::add,
            { width, height -> sizes.add(width to height) }, keepLastFrame = true)

        sink.onFrame(frame(2520, 1680))
        sink.close()
        sink.onFrame(frame(1080, 1920))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))

        assertEquals(1, rendered)
        assertEquals(emptyList<Pair<Int, Int>>(), sizes)
        assertEquals(emptyList<Boolean>(), visibility)
    }

    private fun frame(width: Int, height: Int, rotation: Int = 0) = VideoFrame(
        object : VideoFrame.Buffer {
            override fun getWidth() = width
            override fun getHeight() = height
            override fun retain() = Unit
            override fun release() = Unit
            override fun toI420(): VideoFrame.I420Buffer = error("Not used by the recording sink")
            override fun cropAndScale(x: Int, y: Int, w: Int, h: Int, scaledW: Int, scaledH: Int): VideoFrame.Buffer =
                error("Frames must reach the renderer without cropping")
        }, rotation, 0L,
    )
}
