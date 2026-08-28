package org.tinitalk.media

import android.content.Context
import android.util.Log
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpSender
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class WebRtcCallSession private constructor(
    context: Context,
    private val videoAllowed: Boolean,
    iceServers: List<IceServerData>,
    private val onLocalIceCandidate: (IceCandidateData) -> Unit,
    private val onLocalIceCandidatesRemoved: (List<IceCandidateData>) -> Unit,
    private val onIceRestartNeeded: () -> Unit,
    private val onConnectionStateChanged: (MediaConnectionState) -> Unit,
    private val onRemoteVideoTrack: (VideoRenderSource) -> Unit,
    private val cameraCallbacks: CameraMediaCallbacks,
    private val forceRelay: Boolean,
) : MediaSession, CameraMediaSession {
    private val appContext = context.applicationContext
    private val iceQueue = IceQueue()
    private val coreResources = NativeResourceOwner()
    private val peerResources = NativeResourceOwner()
    private val mediaResources = NativeResourceOwner()
    private val factory: PeerConnectionFactory
    private val audioDeviceModule: JavaAudioDeviceModule
    private val eglBase: EglBase?
    private val audioSource: AudioSource
    private val audioTrack: AudioTrack
    private val sender: RtpSender?
    private val videoSender: RtpSender?
    private val peerConnection: PeerConnection
    private val restartGate = IceRestartGate(ExecutorTaskScheduler())
    private val statsCollector = CallStatsCollector()
    private val closeGate = SessionCloseGate()
    private val closed: Boolean get() = closeGate.closed
    private var active = false
    private var muted = false
    private var cameraController: WebRtcCameraController? = null
    private val cameraExecutor: ExecutorService? = if (videoAllowed) {
        Executors.newSingleThreadExecutor { task ->
            Thread(task, CameraControlThreadName).apply { isDaemon = true }
        }
    } else {
        null
    }
    private val cameraCleanupExecutor: ExecutorService? = if (videoAllowed) {
        Executors.newCachedThreadPool { task ->
            Thread(task, CameraCleanupThreadName).apply { isDaemon = true }
        }
    } else {
        null
    }
    private val cameraQueue = cameraExecutor?.let { executor ->
        CloseableCameraTaskQueue(CameraTaskQueue { task -> executor.execute(task) })
    }
    private val cameraCleanupQueue = cameraCleanupExecutor?.let { executor ->
        CloseableCameraTaskQueue(CameraTaskQueue { task -> executor.execute(task) })
    }
    @Volatile private var cleanupStarted = false
    private val remoteVideoLock = Any()
    private var remoteVideoSource: VideoRenderSource? = null

    init {
        try {
            prepareFactory(context.applicationContext)
            audioDeviceModule = JavaAudioDeviceModule.builder(context.applicationContext)
                .setUseLowLatency(WebRtcPolicy.useLowLatencyAudio)
                .setUseHardwareAcousticEchoCanceler(true)
                .setUseHardwareNoiseSuppressor(true)
                .createAudioDeviceModule()
            coreResources.own(audioDeviceModule::release)

            eglBase = if (videoAllowed) EglBase.create() else null
            eglBase?.let { coreResources.own(it::release) }
            factory = if (eglBase == null) {
                PeerConnectionFactory.builder()
                    .setAudioDeviceModule(audioDeviceModule)
                    .createPeerConnectionFactory()
            } else {
                PeerConnectionFactory.builder()
                    .setAudioDeviceModule(audioDeviceModule)
                    .setVideoEncoderFactory(
                        DefaultVideoEncoderFactory(
                            eglBase.eglBaseContext,
                            true,
                            true,
                        ),
                    )
                    .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
                    .createPeerConnectionFactory()
            }
            coreResources.own(factory::dispose)

            peerConnection = requireNotNull(
                factory.createPeerConnection(
                    rtcConfiguration(iceServers),
                    PeerConnectionObserver(
                        onLocalIceCandidate = onLocalIceCandidate,
                        onLocalIceCandidatesRemoved = onLocalIceCandidatesRemoved,
                        onConnectionChange = { state -> onIceConnectionState(state) },
                        onRemoteVideoTrack = { track ->
                            publishRemoteVideoTrack(track)
                        },
                    ),
                ),
            )
            peerResources.own(peerConnection::dispose)
            peerResources.own(peerConnection::close)

            audioSource = factory.createAudioSource(audioConstraints())
            mediaResources.own(audioSource::dispose)
            audioTrack = factory.createAudioTrack("local_audio", audioSource)
            mediaResources.own(audioTrack::dispose)
            sender = peerConnection.addTrack(audioTrack, listOf("audio"))
            sender?.let { audioSender ->
                mediaResources.own { peerConnection.removeTrack(audioSender) }
                configureAudioSender(audioSender)
            }
            videoSender = if (videoAllowed) {
                requireNotNull(
                    peerConnection.addTransceiver(
                        MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                        RtpTransceiver.RtpTransceiverInit(
                            RtpTransceiver.RtpTransceiverDirection.SEND_RECV,
                        ),
                    ),
                ).sender
            } else {
                null
            }
            applyAudioTrackState()
        } catch (failure: Throwable) {
            cleanupResources(failure)
            throw failure
        }
    }

    override suspend fun createOffer(): String {
        ensureOpen()
        val description = createDescription { observer, constraints ->
            peerConnection.createOffer(observer, constraints)
        }.withOpusOptions()
        setLocalDescription(description)
        return description.description
    }

    override suspend fun acceptOffer(sdp: String): String {
        ensureOpen()
        setRemoteDescription(SessionDescription(SessionDescription.Type.OFFER, sdp))
        val description = createDescription { observer, constraints ->
            peerConnection.createAnswer(observer, constraints)
        }.withOpusOptions()
        setLocalDescription(description)
        return description.description
    }

    override suspend fun setAnswer(sdp: String) {
        ensureOpen()
        setRemoteDescription(SessionDescription(SessionDescription.Type.ANSWER, sdp))
    }

    override suspend fun addIceCandidate(candidate: IceCandidateData) {
        ensureOpen()
        iceQueue.addOrBuffer(candidate).forEach(::addRemoteIceCandidate)
    }

    override suspend fun removeIceCandidates(candidates: List<IceCandidateData>) {
        ensureOpen()
        val applied = iceQueue.remove(candidates)
        if (applied.isNotEmpty()) {
            check(peerConnection.removeIceCandidates(applied.map { it.toWebRtc() }.toTypedArray())) {
                "failed to remove remote ICE candidates"
            }
        }
    }

    override suspend fun restartIce(): String {
        ensureOpen()
        beginRemoteDescription()
        peerConnection.restartIce()
        return createOffer()
    }

    override suspend fun updateIceServers(servers: List<IceServerData>) {
        ensureOpen()
        check(peerConnection.setConfiguration(rtcConfiguration(servers))) { "failed to update ICE servers" }
    }

    override fun beginRemoteDescription() {
        iceQueue.beginRemoteDescription()
    }

    override fun onNetworkChanged() {
        if (!closed) restartGate.onNetworkChanged(onIceRestartNeeded)
    }

    override fun setMuted(muted: Boolean) {
        this.muted = muted
        applyAudioTrackState()
    }

    override fun setActive(active: Boolean) {
        this.active = active
        applyAudioTrackState()
    }

    override fun getStats(onResult: (CallStats) -> Unit) {
        if (closed) return
        runCatching {
            peerConnection.getStats { report ->
                runCatching {
                    if (closed) return@runCatching
                    val stats = statsCollector.collect(
                        report.statsMap.mapValues { (_, item) -> CallStatsSample(item.type, item.members) },
                        System.currentTimeMillis(),
                    )
                    if (!closed) onResult(stats)
                }
            }
        }
    }

    @Synchronized
    override fun startCamera() {
        ensureOpen()
        check(videoAllowed) { "video is not allowed for this call" }
        val controller = cameraController ?: WebRtcCameraController(
            context = appContext,
            factory = factory,
            eglContext = requireNotNull(eglBase).eglBaseContext,
            sender = requireNotNull(videoSender),
            controlQueue = requireNotNull(cameraQueue),
            blockingQueue = requireNotNull(cameraCleanupQueue),
            callbacks = cameraCallbacks,
        ).also { cameraController = it }
        controller.start()
    }

    @Synchronized
    override fun pauseCamera(onDetached: () -> Unit, onReleased: () -> Unit) {
        if (!closed) cameraController?.pause(onDetached, onReleased) else {
            onDetached()
            onReleased()
        }
    }

    @Synchronized
    override fun stopCamera(onDetached: () -> Unit, onReleased: () -> Unit) {
        cameraController?.stop(onDetached, onReleased) ?: run {
            onDetached()
            onReleased()
        }
    }

    @Synchronized
    override fun switchCamera() {
        if (!closed) cameraController?.switchCamera()
    }

    override suspend fun close() {
        closeGate.runOnce(::startClose)
    }

    private fun startClose() {
        runCatching { audioTrack.setEnabled(false) }
        runCatching { audioDeviceModule.setMicrophoneMute(true) }
        runCatching { audioDeviceModule.setSpeakerMute(true) }
        val camera = cameraController
        if (camera == null) {
            cleanupResources()
        } else {
            camera.close(
                afterDetached = { cleanupResources(camera = camera) },
                afterReleased = {},
            )
        }
    }

    @Synchronized
    private fun cleanupResources(
        primaryFailure: Throwable? = null,
        camera: WebRtcCameraController? = cameraController,
    ) {
        if (cleanupStarted) return
        cleanupStarted = true
        closeRemoteVideoSource()
        val cleanup = NativeResourceOwner()
        cleanup.own { peerResources.close(primaryFailure) }
        cleanup.own { mediaResources.close(primaryFailure) }
        cleanup.own(iceQueue::clear)
        cleanup.own(restartGate::close)
        cameraController = null
        try {
            cleanup.close(primaryFailure)
        } finally {
            val releaseCore = {
                runCatching { coreResources.close(primaryFailure) }
                cameraCleanupQueue?.close()
                cameraCleanupExecutor?.shutdown()
                Unit
            }
            if (camera == null) {
                releaseCore()
            } else {
                camera.disposeAfterPeerClosed(releaseCore)
            }
            cameraQueue?.close()
            cameraExecutor?.shutdown()
        }
    }

    private suspend fun setLocalDescription(description: SessionDescription) {
        awaitSdp { observer -> peerConnection.setLocalDescription(observer, description) }
    }

    private suspend fun setRemoteDescription(description: SessionDescription) {
        beginRemoteDescription()
        awaitSdp { observer -> peerConnection.setRemoteDescription(observer, description) }
        iceQueue.markRemoteDescriptionReady().forEach(::addRemoteIceCandidate)
    }

    private fun addRemoteIceCandidate(candidate: IceCandidateData) {
        check(peerConnection.addIceCandidate(candidate.toWebRtc())) { "failed to add remote ICE candidate" }
    }

    private suspend fun createDescription(
        create: (SdpObserver, MediaConstraints) -> Unit,
    ): SessionDescription = suspendCoroutine { continuation ->
        create(
            object : SdpObserver {
                override fun onCreateSuccess(description: SessionDescription) = continuation.resume(description)
                override fun onSetSuccess() = Unit
                override fun onCreateFailure(error: String) = continuation.resumeWithException(IllegalStateException(error))
                override fun onSetFailure(error: String) = continuation.resumeWithException(IllegalStateException(error))
            },
            offerConstraints(videoAllowed),
        )
    }

    private suspend fun awaitSdp(set: (SdpObserver) -> Unit) = suspendCoroutine { continuation ->
        set(
            object : SdpObserver {
                override fun onCreateSuccess(description: SessionDescription) = Unit
                override fun onSetSuccess() = continuation.resume(Unit)
                override fun onCreateFailure(error: String) = continuation.resumeWithException(IllegalStateException(error))
                override fun onSetFailure(error: String) = continuation.resumeWithException(IllegalStateException(error))
            },
        )
    }

    private fun ensureOpen() {
        check(!closed) { "media session is closed" }
    }

    private fun applyAudioTrackState() {
        audioTrack.setEnabled(WebRtcPolicy.audioTrackEnabled(active, muted))
        audioDeviceModule.setMicrophoneMute(WebRtcPolicy.microphoneMuted(active, muted))
        audioDeviceModule.setSpeakerMute(WebRtcPolicy.speakerMuted(active))
    }

    private fun publishRemoteVideoTrack(track: VideoTrack) {
        val renderContext = eglBase?.eglBaseContext
        if (!shouldExposeRemoteVideo(videoAllowed, renderContext != null, closed || cleanupStarted)) return
        val source = VideoRenderSource(track, requireNotNull(renderContext))
        val previous = synchronized(remoteVideoLock) {
            if (closed || cleanupStarted) {
                source.close()
                return
            }
            remoteVideoSource.also { remoteVideoSource = source }
        }
        previous?.close()
        runCatching { onRemoteVideoTrack(source) }
            .onFailure {
                synchronized(remoteVideoLock) {
                    if (remoteVideoSource === source) remoteVideoSource = null
                }
                source.close()
            }
    }

    private fun closeRemoteVideoSource() {
        val source = synchronized(remoteVideoLock) {
            remoteVideoSource.also { remoteVideoSource = null }
        }
        source?.close()
    }

    private fun configureAudioSender(sender: RtpSender) {
        val parameters = sender.parameters
        WebRtcPolicy.configureAudioEncodings(parameters.encodings)
        if (!sender.setParameters(parameters)) {
            Log.w(LogTag, "failed to enable adaptive audio packet time")
        }
    }

    private fun onIceConnectionState(state: PeerConnection.IceConnectionState) {
        if (closed) return
        val connectionState = when (state) {
            PeerConnection.IceConnectionState.NEW,
            PeerConnection.IceConnectionState.CHECKING -> MediaConnectionState.Connecting
            PeerConnection.IceConnectionState.CONNECTED,
            PeerConnection.IceConnectionState.COMPLETED -> MediaConnectionState.Connected
            PeerConnection.IceConnectionState.DISCONNECTED -> MediaConnectionState.Disconnected
            PeerConnection.IceConnectionState.FAILED -> MediaConnectionState.Failed
            PeerConnection.IceConnectionState.CLOSED -> MediaConnectionState.Closed
        }
        onConnectionStateChanged(connectionState)
        when (state) {
            PeerConnection.IceConnectionState.DISCONNECTED -> restartGate.onDisconnected(onIceRestartNeeded)
            PeerConnection.IceConnectionState.FAILED -> restartGate.onFailed(onIceRestartNeeded)
            PeerConnection.IceConnectionState.CONNECTED,
            PeerConnection.IceConnectionState.COMPLETED,
            PeerConnection.IceConnectionState.CLOSED -> restartGate.onConnected()
            else -> Unit
        }
    }

    private fun rtcConfiguration(iceServers: List<IceServerData>) =
        PeerConnection.RTCConfiguration(iceServers.map { it.toWebRtc() }).also {
            WebRtcPolicy.configureConnection(it, forceRelay)
        }

    private fun IceServerData.toWebRtc(): PeerConnection.IceServer {
        val builder = PeerConnection.IceServer.builder(urls)
        if (username.isNotEmpty() || password.isNotEmpty()) {
            builder.setUsername(username)
            builder.setPassword(password)
        }
        return builder.createIceServer()
    }

    private fun IceCandidateData.toWebRtc() = IceCandidate(sdpMid, sdpMLineIndex, candidate)

    private fun SessionDescription.withOpusOptions(): SessionDescription =
        SessionDescription(type, OpusSdp.enableNetworkResilience(description))

    companion object {
        private const val LogTag = "TiniTalkCall"
        private const val CameraControlThreadName = "TiniTalkCameraControl"
        private const val CameraCleanupThreadName = "TiniTalkCameraCleanup"
        @Volatile private var factoryReady = false

        fun create(
            context: Context,
            videoAllowed: Boolean,
            iceServers: List<IceServerData> = emptyList(),
            forceRelay: Boolean = false,
            onLocalIceCandidate: (IceCandidateData) -> Unit = {},
            onLocalIceCandidatesRemoved: (List<IceCandidateData>) -> Unit = {},
            onIceRestartNeeded: () -> Unit = {},
            onConnectionStateChanged: (MediaConnectionState) -> Unit = {},
            onRemoteVideoTrack: (VideoRenderSource) -> Unit = {},
            cameraCallbacks: CameraMediaCallbacks = CameraMediaCallbacks(),
        ): WebRtcCallSession = WebRtcCallSession(
            context,
            videoAllowed,
            iceServers,
            onLocalIceCandidate,
            onLocalIceCandidatesRemoved,
            onIceRestartNeeded,
            onConnectionStateChanged,
            onRemoteVideoTrack,
            cameraCallbacks,
            forceRelay,
        )

        @Synchronized
        private fun prepareFactory(context: Context) {
            if (factoryReady) return
            val options = PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
            PeerConnectionFactory.initialize(options)
            factoryReady = true
        }

        private fun audioConstraints() = MediaConstraints().apply {
            optional.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            optional.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            optional.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
        }

        private fun offerConstraints(videoAllowed: Boolean) = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            if (!videoAllowed) {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            }
        }
    }
}
