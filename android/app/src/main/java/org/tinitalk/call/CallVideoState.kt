package org.tinitalk.call

import org.webrtc.VideoTrack
import java.util.concurrent.CopyOnWriteArraySet

enum class CameraFacing {
    Front,
    Back,
}

data class CallVideoState<out Track>(
    val callId: String? = null,
    val allowed: Boolean = false,
    val requested: Boolean = false,
    val permissionGranted: Boolean = false,
    val sending: Boolean = false,
    val facing: CameraFacing = CameraFacing.Front,
    val localTrack: Track? = null,
    val remoteTrack: Track? = null,
    val failure: String? = null,
) {
    fun configured(nextCallId: String, videoAllowed: Boolean): CallVideoState<Track> =
        if (callId == nextCallId) {
            if (videoAllowed) copy(allowed = true) else CallVideoState(callId = nextCallId)
        } else {
            CallVideoState(callId = nextCallId, allowed = videoAllowed)
        }

    fun withPermission(callbackCallId: String, granted: Boolean): CallVideoState<Track> =
        if (callId == callbackCallId) copy(permissionGranted = granted) else this

    fun request(callbackCallId: String, permissionGranted: Boolean): CallVideoState<Track> =
        if (callId == callbackCallId && allowed && permissionGranted) {
            copy(requested = true, permissionGranted = true, failure = null)
        } else {
            this
        }

    fun withLocalTrack(callbackCallId: String, track: @UnsafeVariance Track?): CallVideoState<Track> =
        if (callId == callbackCallId) copy(localTrack = track) else this

    fun withRemoteTrack(callbackCallId: String, track: @UnsafeVariance Track?): CallVideoState<Track> =
        if (callId == callbackCallId) copy(remoteTrack = track) else this

    fun captureStarted(callbackCallId: String, cameraFacing: CameraFacing): CallVideoState<Track> =
        if (callId == callbackCallId && allowed && requested && permissionGranted) {
            copy(sending = true, facing = cameraFacing, failure = null)
        } else {
            this
        }

    fun captureStopped(callbackCallId: String): CallVideoState<Track> =
        if (callId == callbackCallId) copy(sending = false) else this

    fun withFacing(callbackCallId: String, cameraFacing: CameraFacing): CallVideoState<Track> =
        if (callId == callbackCallId) copy(facing = cameraFacing, failure = null) else this

    fun manualOff(callbackCallId: String): CallVideoState<Track> =
        if (callId == callbackCallId) {
            copy(
                requested = false,
                sending = false,
                facing = CameraFacing.Front,
                localTrack = null,
                failure = null,
            )
        } else {
            this
        }

    fun failed(callbackCallId: String, message: String): CallVideoState<Track> =
        if (callId == callbackCallId) {
            copy(sending = false, facing = CameraFacing.Front, localTrack = null, failure = message)
        } else {
            this
        }
}

object VideoCallStateStore {
    private val listeners = CopyOnWriteArraySet<(CallVideoState<VideoTrack>) -> Unit>()

    @Volatile
    private var current = CallVideoState<VideoTrack>()

    fun snapshot(): CallVideoState<VideoTrack> = current

    fun observe(listener: (CallVideoState<VideoTrack>) -> Unit) {
        listeners += listener
        listener(current)
    }

    fun removeObserver(listener: (CallVideoState<VideoTrack>) -> Unit) {
        listeners -= listener
    }

    @Synchronized
    fun publish(state: CallVideoState<VideoTrack>) {
        current = state
        listeners.forEach { it(state) }
    }

    @Synchronized
    fun reset() = publish(CallVideoState())
}
