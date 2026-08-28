package org.tinitalk.media

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.tinitalk.call.CameraFacing
import org.webrtc.Camera1Enumerator
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraEnumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.CapturerObserver
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpSender
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoTrack
import org.webrtc.VideoFrame
import java.util.concurrent.atomic.AtomicBoolean

internal fun orderedCameraNames(enumerator: CameraEnumerator): List<String> {
    val names = enumerator.deviceNames.toList()
    return names.filter(enumerator::isFrontFacing) + names.filterNot(enumerator::isFrontFacing)
}

internal fun oppositeFacingCameraName(
    enumerator: CameraEnumerator,
    facing: CameraFacing,
): String? = enumerator.deviceNames.firstOrNull { deviceName ->
    when (facing) {
        CameraFacing.Front -> enumerator.isBackFacing(deviceName)
        CameraFacing.Back -> enumerator.isFrontFacing(deviceName)
    }
}

internal data class CameraEnumeratorBackend(
    val name: String,
    val create: () -> CameraEnumerator,
)

internal data class EnumeratedCameraDevice(
    val enumerator: CameraEnumerator,
    val deviceName: String,
    val facing: CameraFacing,
    val oppositeTarget: String?,
)

internal fun enumerateCameraBackends(
    backends: List<CameraEnumeratorBackend>,
    onFailure: (String, Throwable) -> Unit,
): List<EnumeratedCameraDevice> = backends.flatMap { backend ->
    runCatching {
        val enumerator = backend.create()
        val names = orderedCameraNames(enumerator)
        names.map { deviceName ->
            val facing = if (enumerator.isFrontFacing(deviceName)) CameraFacing.Front else CameraFacing.Back
            val opposite = names.firstOrNull { candidate ->
                when (facing) {
                    CameraFacing.Front -> enumerator.isBackFacing(candidate)
                    CameraFacing.Back -> enumerator.isFrontFacing(candidate)
                }
            }
            EnumeratedCameraDevice(enumerator, deviceName, facing, opposite)
        }
    }.getOrElse { failure ->
        onFailure(backend.name, failure)
        emptyList()
    }
}

internal class WebRtcCameraController(
    context: Context,
    factory: PeerConnectionFactory,
    eglContext: EglBase.Context,
    sender: RtpSender,
    controlQueue: CameraTaskQueue,
    blockingQueue: CameraTaskQueue,
    callbacks: CameraMediaCallbacks,
) {
    private val lifecycle = SerializedCameraLifecycle(
        controlQueue = controlQueue,
        blockingQueue = blockingQueue,
        mainQueue = AndroidMainCameraQueue,
        provider = WebRtcCameraAttemptProvider(context, factory, eglContext, sender),
        callbacks = CameraLifecycleCallbacks(
            onLocalTrackChanged = callbacks.onLocalTrackChanged,
            onCaptureStarted = callbacks.onCaptureStarted,
            onCaptureInvalidated = callbacks.onCaptureInvalidated,
            onCaptureStopped = callbacks.onCaptureStopped,
            onFacingChanged = callbacks.onFacingChanged,
            onFailure = callbacks.onFailure,
        ),
    )

    fun start() = lifecycle.start()
    fun pause(afterDetached: () -> Unit = {}, afterReleased: () -> Unit = {}) = lifecycle.pause(
        afterDetached = { AndroidMainCameraQueue.execute(afterDetached) },
        afterReleased = { AndroidMainCameraQueue.execute(afterReleased) },
    )
    fun stop(afterDetached: () -> Unit = {}, afterReleased: () -> Unit = {}) = lifecycle.stop(
        afterDetached = { AndroidMainCameraQueue.execute(afterDetached) },
        afterReleased = { AndroidMainCameraQueue.execute(afterReleased) },
    )
    fun switchCamera() = lifecycle.switchCamera()
    fun close(afterDetached: () -> Unit, afterReleased: () -> Unit) = lifecycle.close(
        afterDetached = { AndroidMainCameraQueue.execute(afterDetached) },
        afterReleased = { AndroidMainCameraQueue.execute(afterReleased) },
    )
    fun disposeAfterPeerClosed(afterAllReleased: () -> Unit = {}) =
        lifecycle.disposeAfterPeerClosed(afterAllReleased)
}

private class WebRtcCameraAttemptProvider(
    private val context: Context,
    private val factory: PeerConnectionFactory,
    private val eglContext: EglBase.Context,
    private val sender: RtpSender,
) : CameraAttemptProvider<VideoTrack> {
    private data class Descriptor(
        val enumerator: CameraEnumerator,
        val deviceName: String,
    )

    private val descriptors = mutableMapOf<String, Descriptor>()

    override fun candidates(): List<CameraAttemptCandidate> {
        descriptors.clear()
        return enumerateCameraBackends(cameraBackends()) { backend, failure ->
            Log.w(LogTag, "failed to enumerate $backend", failure)
        }.mapIndexed { deviceIndex, device ->
                val id = "$deviceIndex:${device.deviceName}"
                descriptors[id] = Descriptor(device.enumerator, device.deviceName)
                CameraAttemptCandidate(
                    id = id,
                    facing = device.facing,
                    oppositeTarget = device.oppositeTarget,
                )
        }
    }

    override fun create(
        candidate: CameraAttemptCandidate,
        events: CameraAttemptEvents,
    ): CameraAttempt<VideoTrack> {
        val descriptor = requireNotNull(descriptors[candidate.id]) { "stale camera candidate" }
        val owner = NativeResourceOwner()
        try {
            val capturer = requireNotNull(
                descriptor.enumerator.createCapturer(descriptor.deviceName, events.asWebRtc()),
            ) { "failed to create camera ${descriptor.deviceName}" }
            owner.own(capturer::dispose)
            val textureHelper = requireNotNull(
                SurfaceTextureHelper.create(CameraThreadName, eglContext),
            ) { "failed to create camera texture helper" }
            owner.own(textureHelper::dispose)
            val source = factory.createVideoSource(false)
            owner.own(source::dispose)
            val opening = AtomicBoolean(false)
            capturer.initialize(
                textureHelper,
                context,
                OpeningCapturerObserver(source.capturerObserver, opening),
            )
            val track = factory.createVideoTrack(LocalVideoTrackId, source)
            owner.own(track::dispose)
            val lease = SenderTrackLease(
                track = track,
                attach = { localTrack -> sender.setTrack(localTrack, false) },
                detach = { sender.setTrack(null, false) },
                disable = { localTrack -> localTrack.setEnabled(false) },
                dispose = { owner.close() },
            )
            if (!lease.attach()) {
                throw CameraAttemptCreationException(
                    message = "failed to attach local video track",
                    fatal = true,
                )
            }
            return WebRtcCameraAttempt(
                enumerator = descriptor.enumerator,
                capturer = capturer,
                localTrack = track,
                lease = lease,
                openingState = opening,
                initialFacing = candidate.facing,
            )
        } catch (failure: Throwable) {
            runCatching { owner.close(failure) }
            throw failure
        }
    }

    private fun cameraBackends(): List<CameraEnumeratorBackend> = buildList {
        if (runCatching { Camera2Enumerator.isSupported(context) }.getOrDefault(false)) {
            add(CameraEnumeratorBackend("Camera2") { Camera2Enumerator(context) })
        }
        add(CameraEnumeratorBackend("Camera1") { Camera1Enumerator(true) })
    }
}

private class WebRtcCameraAttempt(
    private val enumerator: CameraEnumerator,
    private val capturer: CameraVideoCapturer,
    override val localTrack: VideoTrack,
    private val lease: SenderTrackLease<VideoTrack>,
    private val openingState: AtomicBoolean,
    initialFacing: CameraFacing,
) : CameraAttempt<VideoTrack> {
    override var facing = initialFacing
    private val capturing = AtomicBoolean(false)
    private val detached = AtomicBoolean(false)
    override val opening: Boolean get() = openingState.get()

    override fun oppositeFacingDevice(): String? = oppositeFacingCameraName(enumerator, facing)

    override fun start() {
        openingState.set(true)
        capturing.set(true)
        try {
            capturer.startCapture(CaptureWidth, CaptureHeight, CaptureFps)
        } catch (failure: Throwable) {
            openingState.set(false)
            capturing.set(false)
            throw failure
        }
    }

    override fun stop() {
        if (!capturing.getAndSet(false)) return
        capturer.stopCapture()
    }

    override fun detach(): CameraAttemptRelease {
        if (!detached.compareAndSet(false, true)) return CameraAttemptRelease()
        return lease.release()
    }

    override fun dispose() = lease.dispose()

    override fun switchCamera(targetDevice: String, events: CameraSwitchEvents) {
        openingState.set(true)
        try {
            capturer.switchCamera(
                object : CameraVideoCapturer.CameraSwitchHandler {
                    override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                        openingState.set(false)
                        events.onDone(if (isFrontCamera) CameraFacing.Front else CameraFacing.Back)
                    }

                    override fun onCameraSwitchError(errorDescription: String) {
                        openingState.set(false)
                        events.onFailure("camera switch failed: $errorDescription")
                    }
                },
                targetDevice,
            )
        } catch (failure: Throwable) {
            openingState.set(false)
            throw failure
        }
    }
}

private class OpeningCapturerObserver(
    private val delegate: CapturerObserver,
    private val opening: AtomicBoolean,
) : CapturerObserver {
    override fun onCapturerStarted(success: Boolean) {
        opening.set(false)
        delegate.onCapturerStarted(success)
    }

    override fun onCapturerStopped() {
        opening.set(false)
        delegate.onCapturerStopped()
    }

    override fun onFrameCaptured(frame: VideoFrame) = delegate.onFrameCaptured(frame)
}

private fun CameraAttemptEvents.asWebRtc() = object : CameraVideoCapturer.CameraEventsHandler {
    override fun onCameraError(errorDescription: String) = onFailure("camera error: $errorDescription")
    override fun onCameraDisconnected() = onFailure("camera disconnected")
    override fun onCameraFreezed(errorDescription: String) = onFailure("camera froze: $errorDescription")
    override fun onCameraOpening(cameraName: String) = Unit
    override fun onFirstFrameAvailable() = onFirstFrame()
    override fun onCameraClosed() = Unit
}

private object AndroidMainCameraQueue : CameraTaskQueue {
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    override fun execute(task: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) task() else handler.post(task)
    }
}

private const val LogTag = "TiniTalkCamera"
private const val CameraThreadName = "TiniTalkCameraCapture"
private const val LocalVideoTrackId = "local_video"
private const val CaptureWidth = 640
private const val CaptureHeight = 360
private const val CaptureFps = 15
