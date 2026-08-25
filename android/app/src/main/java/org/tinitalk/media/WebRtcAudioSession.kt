package org.tinitalk.media

import android.content.Context
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpSender
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class WebRtcAudioSession private constructor(
    context: Context,
    iceServers: List<IceServerData>,
    private val onLocalIceCandidate: (IceCandidateData) -> Unit,
    forceRelay: Boolean,
) : MediaSession {
    private val iceQueue = IceQueue()
    private val factory: PeerConnectionFactory
    private val audioSource: AudioSource
    private val audioTrack: AudioTrack
    private val sender: RtpSender?
    private val peerConnection: PeerConnection
    @Volatile private var closed = false

    init {
        prepareFactory(context.applicationContext)
        val audioDeviceModule = JavaAudioDeviceModule.builder(context.applicationContext)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()

        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioDeviceModule)
            .createPeerConnectionFactory()

        val config = PeerConnection.RTCConfiguration(iceServers.map { it.toWebRtc() })
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        config.iceTransportsType = WebRtcPolicy.iceTransport(forceRelay)

        peerConnection = requireNotNull(
            factory.createPeerConnection(
                config,
                PeerConnectionObserver(onLocalIceCandidate = onLocalIceCandidate),
            ),
        )
        audioSource = factory.createAudioSource(audioConstraints())
        audioTrack = factory.createAudioTrack("local_audio", audioSource)
        sender = peerConnection.addTrack(audioTrack, listOf("audio"))
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
        iceQueue.addOrBuffer(candidate).forEach { peerConnection.addIceCandidate(it.toWebRtc()) }
    }

    override suspend fun restartIce(): String {
        ensureOpen()
        peerConnection.restartIce()
        return createOffer()
    }

    override fun setMuted(muted: Boolean) {
        audioTrack.setEnabled(!muted)
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        iceQueue.clear()
        sender?.let { peerConnection.removeTrack(it) }
        audioTrack.dispose()
        audioSource.dispose()
        peerConnection.close()
        peerConnection.dispose()
        factory.dispose()
    }

    private suspend fun setLocalDescription(description: SessionDescription) {
        awaitSdp { observer -> peerConnection.setLocalDescription(observer, description) }
    }

    private suspend fun setRemoteDescription(description: SessionDescription) {
        awaitSdp { observer -> peerConnection.setRemoteDescription(observer, description) }
        iceQueue.markRemoteDescriptionReady().forEach { peerConnection.addIceCandidate(it.toWebRtc()) }
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
            offerConstraints(),
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
        @Volatile private var factoryReady = false

        fun create(
            context: Context,
            iceServers: List<IceServerData> = emptyList(),
            forceRelay: Boolean = false,
            onLocalIceCandidate: (IceCandidateData) -> Unit = {},
        ): WebRtcAudioSession = WebRtcAudioSession(context, iceServers, onLocalIceCandidate, forceRelay)

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

        private fun offerConstraints() = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }
    }
}
