package org.tinitalk.ui.call

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.tinitalk.media.VideoRenderSource
import org.webrtc.EglBase
import org.webrtc.EglRenderer
import org.webrtc.GlRectDrawer
import org.webrtc.ThreadUtils
import java.util.concurrent.CountDownLatch

/**
 * A shared screen changes aspect ratio and is zoomed/panned inside a clipped Compose viewport.
 * TextureView participates in that composition instead of maintaining a separate SurfaceView crop.
 */
@Composable
internal fun ScreenVideoRenderer(
    source: VideoRenderSource,
    modifier: Modifier = Modifier,
    contentDescription: String?,
    onFrameSizeChanged: (Int, Int) -> Unit,
    onFrameVisibilityChanged: (Boolean) -> Unit,
    keepLastFrame: Boolean = true,
) {
    key(source, keepLastFrame) {
        val context = LocalContext.current
        val currentSize = rememberUpdatedState(onFrameSizeChanged)
        val currentVisibility = rememberUpdatedState(onFrameVisibilityChanged)
        val handle = remember {
            ScreenVideoRendererHandle(context, source,
                keepLastFrame = keepLastFrame,
                onFrameSizeChanged = { width, height -> currentSize.value(width, height) },
                onVisibilityChanged = { currentVisibility.value(it) },
            )
        }
        DisposableEffect(handle) {
            onDispose { handle.close() }
        }
        AndroidView(
            factory = { handle.view.also { handle.start() } },
            modifier = modifier,
            update = { it.contentDescription = contentDescription },
        )
    }
}

private class ScreenVideoRendererHandle(
    context: Context,
    private val source: VideoRenderSource,
    private val onFrameSizeChanged: (Int, Int) -> Unit,
    private val onVisibilityChanged: (Boolean) -> Unit,
    private val keepLastFrame: Boolean,
) : TextureView.SurfaceTextureListener, AutoCloseable {
    val view = TextureView(context).apply { surfaceTextureListener = this@ScreenVideoRendererHandle }
    private val renderer = EglRenderer("SharedScreen")
    private var started = false
    private var sink: GuardedRendererSink? = null

    fun start() {
        if (started) return
        renderer.init(source.eglContext, EglBase.CONFIG_PLAIN, GlRectDrawer())
        started = true
        view.surfaceTexture?.let { onSurfaceTextureAvailable(it, view.width, view.height) }
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        if (!started || sink != null) return
        renderer.setLayoutAspectRatio(width.toFloat() / height.coerceAtLeast(1))
        renderer.createEglSurface(surface)
        // Attach only after EGL surface creation is queued, including after returning from background.
        val next = GuardedRendererSink(renderer, onVisibilityChanged, onFrameSizeChanged, keepLastFrame = keepLastFrame)
        sink = next
        if (!source.attach(next)) next.close()
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        if (started) renderer.setLayoutAspectRatio(width.toFloat() / height.coerceAtLeast(1))
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        detachSink()
        if (started) {
            // The render thread must stop using the texture before TextureView releases it.
            val released = CountDownLatch(1)
            renderer.releaseEglSurface { released.countDown() }
            ThreadUtils.awaitUninterruptibly(released)
        }
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    override fun close() {
        if (!started) return
        started = false
        detachSink()
        renderer.release()
    }

    private fun detachSink() {
        val attached = sink ?: return
        sink = null
        attached.close()
        source.detach(attached)
    }
}
