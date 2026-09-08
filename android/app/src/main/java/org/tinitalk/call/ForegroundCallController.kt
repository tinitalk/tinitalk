package org.tinitalk.call

import android.content.Intent
import android.util.Log
import com.google.gson.JsonObject
import org.tinitalk.data.AccountId
import org.tinitalk.data.signal.SignalEvent
import org.tinitalk.data.signal.SignalFailure
import org.tinitalk.media.IceCandidateData
import org.tinitalk.media.IceServerData
import org.tinitalk.media.CallStats
import org.tinitalk.media.MediaConnectionState
import org.tinitalk.media.MediaSession
import org.tinitalk.media.CameraMediaSession
import org.tinitalk.media.ScreenMediaSession
import org.tinitalk.media.CancellableTask
import org.tinitalk.media.ExecutorTaskScheduler
import org.tinitalk.media.TaskScheduler
import org.tinitalk.media.VideoRenderSource
import java.time.Instant
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal const val CredentialRefreshLeadMillis = 60_000L
internal const val SecurityCodeTimeoutMillis = 30_000L

class ForegroundCallController(
    private val signal: SignalClient,
    private val mediaFactory: (
        String,
        Boolean,
        List<IceServerData>,
        (IceCandidateData) -> Unit,
        (List<IceCandidateData>) -> Unit,
        () -> Unit,
    ) -> MediaSession,
    private val ids: EventIds = UuidEventIds(),
    private val scheduler: TaskScheduler = ExecutorTaskScheduler(),
    private val onVideoStateChanged: (CallVideoState<VideoRenderSource>) -> Unit = {},
    private val prepareCameraStart: (String, Long) -> Boolean = { _, _ -> true },
    private val onCameraLeaseReleased: (Long) -> Unit = {},
    private val accountId: AccountId,
    private val selfLogin: String = "",
    private val prepareScreenStart: (String) -> Boolean = { false },
    private val onScreenReleased: () -> Unit = {},
    private val onSecurityStateChanged: (String, CallSecurityState) -> Unit = { _, _ -> },
) {
    constructor(
        signal: SignalClient,
        accountId: AccountId,
        mediaFactory: (
            Boolean,
            List<IceServerData>,
            (IceCandidateData) -> Unit,
            (List<IceCandidateData>) -> Unit,
            () -> Unit,
        ) -> MediaSession,
        ids: EventIds = UuidEventIds(),
        scheduler: TaskScheduler = ExecutorTaskScheduler(),
    ) : this(
        signal = signal,
        accountId = accountId,
        mediaFactory = { _, videoAllowed, servers, onIce, onIceRemoved, onRestart ->
            mediaFactory(videoAllowed, servers, onIce, onIceRemoved, onRestart)
        },
        ids = ids,
        scheduler = scheduler,
    )

    private data class LocalIceEvent(val sequence: Long, val event: SignalEvent)
    private data class SASParties(
        val callId: String,
        val caller: String,
        val callee: String,
        val localIsCaller: Boolean,
    )

    private var session: MediaSession? = null
    private var active = false
    private var muted = false
    private var callId: String? = null
    private var iceServers: List<IceServerData> = emptyList()
    private var videoAllowed = false
    private var configuredCallId: String? = null
    private var acceptedCallId: String? = null
    private var offerStartedCallId: String? = null
    private var offerAwaitingAnswerCallId: String? = null
    private var restartInFlightCallId: String? = null
    private var restartAgainCallId: String? = null
    private var restartRequestedCallId: String? = null
    private var restartRequestID: String? = null
    private var pendingRestart: SignalEvent? = null
    private var pendingRestartRequest: SignalEvent? = null
    @Volatile private var localIceGeneration: String? = null
    private var remoteIceGeneration: String? = null
    private val localCandidateGenerations = mutableMapOf<IceCandidateData, String?>()
    private var credentialRefreshTask: CancellableTask? = null
    private var iceRetryTask: CancellableTask? = null
    private var iceRetryAtMillis: Long? = null
    private var restartRetryTask: CancellableTask? = null
    private var restartRequestRetryTask: CancellableTask? = null
    private var pendingOffer: SignalEvent? = null
    private val pendingIce = ArrayDeque<Pair<String, IceCandidateData>>()
    private var nextLocalIceSequence = 0L
    private val recentLocalIceEvents = linkedMapOf<String, LocalIceEvent>()
    private val rateLimitedIceEvents = linkedMapOf<String, LocalIceEvent>()
    private var videoState = CallVideoState<VideoRenderSource>()
    private var screenPermission: Intent? = null
    private var screenStartEventId: String? = null
    private var screenStartSubmitted = false
    private var screenPreparationId: String? = null
    private var screenCameraReadyId: String? = null
    private var screenStopping = false
    private var screenTimeout: CancellableTask? = null
    private var capturingVideoCallId: String? = null
    private val weakNetworkVideoGate = WeakNetworkVideoGate()
    private var foregroundCallId: String? = null
    private var cameraStartBlocked = false
    private var cameraStartSubmitted = false
    private var nextCameraLease = 0L
    private var activeCameraLease: Long? = null
    private var nextCameraRetirement = 0L
    private var cameraRetirement: Long? = null
    private var cameraDetachPending = false
    private var cameraReleasePending = false
    private var cameraReleaseFailed = false
    private val cameraDetachWaiters = ArrayDeque<() -> Unit>()
    private val cameraReleaseWaiters = ArrayDeque<() -> Unit>()
    private var cameraTransitionGeneration = 0L
    private var closing = false
    private var closed = false
    private val closeWaiters = ArrayDeque<() -> Unit>()
    private var sasParties: SASParties? = null
    private var sasHandshake: CallSASHandshake? = null
    private var sasTimeoutTask: CancellableTask? = null
    private var sasState: CallSecurityState? = null

    @Synchronized
    fun onSignalEvent(snapshot: CallSnapshot, event: SignalEvent) {
        val parties = sasParties
        if (parties != null && parties.callId != event.callId) {
            // A crossed call may adopt the server's canonical ID before SAS starts.
            // All subsequent media and security messages must belong to that call.
            val canonicalCrossedAccept = event.type == "call.accept" && snapshot.phase == CallPhase.Active &&
                event.payload["crossed"]?.asBoolean == true && sasState == null && sasHandshake == null
            if (!canonicalCrossedAccept) return
        }
        when (event.type) {
            "call.accept" -> if (snapshot.phase == CallPhase.Active) {
                val offerer = !event.payload.has("offerer") || event.payload["offerer"].asBoolean
                alignSASRole(event.callId, offerer, event.payload["crossed"]?.asBoolean == true)
                acceptedCallId = event.callId.takeIf { offerer }
                if (offerer) startOfferWhenReady(event.callId)
            }
            "rtc.offer" -> {
                if (configuredCallId == event.callId) {
                    answerOffer(event)
                } else {
                    pendingOffer = event
                }
            }
            "rtc.config" -> handleRtcConfig(event)
            "rtc.screen" -> onScreenState(event)
            "rtc.answer" -> session?.takeIf { callId == event.callId }?.let { media ->
                sasHandshake?.takeIf { sasParties?.callId == event.callId }
                    ?.recordRemoteSdp(event.payload["sdp"].asString)
                runBlockingLite { media.setAnswer(event.payload["sdp"].asString) }
                completeLocalOffer(event.callId)
            }
            "rtc.sas.commit" -> sasHandshake?.takeIf { sasParties?.callId == event.callId }?.onCommitment(event.payload)
            "rtc.sas.key" -> sasHandshake?.takeIf { sasParties?.callId == event.callId }?.onKey(event.payload)
            "rtc.sas.reveal" -> sasHandshake?.takeIf { sasParties?.callId == event.callId }?.onReveal(event.payload)
            "rtc.video" -> {
                val enabled = event.payload["enabled"]
                    ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }
                    ?.asBoolean
                    ?: return
                updateVideoState(videoState.withRemoteSending(event.callId, enabled))
            }
            "rtc.restart" -> {
                restartInFlightCallId = event.callId
                clearPendingRestartRequest(event.callId)
                remoteIceGeneration = event.id
                session?.beginRemoteDescription()
            }
            "rtc.restart.request" -> restartIce(event.callId)
            "rtc.ice" -> {
                val generation = event.payload.restartID()
                if (generation != null && generation != remoteIceGeneration) return
                if (event.payload.isIceRemoval()) {
                    removeIceCandidates(event.callId, event.payload.parseIceCandidates())
                    return
                }
                val candidate = IceCandidateData(
                    sdpMid = event.payload["sdp_mid"].asString,
                    sdpMLineIndex = event.payload["sdp_mline_index"].asInt,
                    candidate = event.payload["candidate"].asString,
                )
                val media = session
                if (media != null && callId == event.callId) {
                    runBlockingLite { media.addIceCandidate(candidate) }
                } else {
                    if (pendingIce.size == SignalEvent.EVENT_BUFFER_LIMIT) pendingIce.removeFirst()
                    pendingIce.addLast(event.callId to candidate)
                }
            }
            "call.reject", "call.cancel", "call.end", "call.expire" -> close()
        }
    }

    @Synchronized
    fun setMuted(muted: Boolean) {
        this.muted = muted
        session?.setMuted(muted)
    }

    @Synchronized
    fun prepareSecurityCode(callId: String, peerLogin: String, localIsCaller: Boolean) {
        if (selfLogin.isBlank() || peerLogin.isBlank()) return
        val caller = if (localIsCaller) selfLogin else peerLogin
        val callee = if (localIsCaller) peerLogin else selfLogin
        val next = SASParties(callId, caller, callee, localIsCaller)
        if (sasParties == next) return
        resetSAS()
        sasState = null
        sasParties = next
    }

    @Synchronized
    fun setActive(active: Boolean) {
        this.active = active
        session?.setActive(active)
    }

    @Synchronized
    fun setCameraForeground(callId: String, foreground: Boolean, permissionGranted: Boolean) {
        val wasForeground = foregroundCallId == callId
        if (foreground) {
            foregroundCallId = callId
            if (!wasForeground) cameraStartBlocked = false
        } else if (foregroundCallId == callId) {
            foregroundCallId = null
            cameraStartSubmitted = false
        }
        updateVideoState(videoState.withPermission(callId, permissionGranted))
        if (!foreground || !permissionGranted) {
            pauseCamera(callId)
        } else {
            reconcileCamera(callId)
        }
    }

    @Synchronized
    fun setCameraRequested(callId: String, requested: Boolean, permissionGranted: Boolean = true) {
        if (videoState.callId != callId || !videoState.allowed) return
        if (videoState.screen.active || screenStopping) return
        cameraStartBlocked = false
        if (requested) {
            updateVideoState(videoState.request(callId, permissionGranted))
            reconcileCamera(callId)
        } else {
            cameraStartSubmitted = false
            updateVideoState(videoState.manualOff(callId))
            requestCameraStop(session)
        }
    }

    @Synchronized
    fun switchCamera(callId: String) {
        if (!cameraEligible(callId)) return
        try {
            (session as? CameraMediaSession)?.switchCamera()
        } catch (failure: Throwable) {
            cameraFailed(callId, failure.message ?: "camera switch failed")
        }
    }

    @Synchronized
    fun onLocalVideoTrack(callId: String, track: VideoRenderSource?) {
        if (videoState.callId != callId || videoState.screen.active || screenStopping) {
            track?.close()
            return
        }
        updateVideoState(videoState.withLocalTrack(callId, track))
    }

    @Synchronized
    fun onRemoteVideoTrack(callId: String, track: VideoRenderSource?) {
        if (videoState.callId != callId) {
            track?.close()
            return
        }
        updateVideoState(videoState.withRemoteTrack(callId, track))
    }

    @Synchronized
    fun onCameraCaptureStarted(callId: String, facing: CameraFacing) {
        if (videoState.callId == callId && videoState.screen.active) {
            requestCameraStop(session)
            return
        }
        if (!cameraEligible(callId)) {
            pauseCamera(callId)
            return
        }
        cameraStartSubmitted = false
        updateVideoState(videoState.captureStarted(callId, facing))
        updateCapturingVideoCall(callId, enabled = true)
    }

    @Synchronized
    fun onCameraCaptureInvalidated(callId: String) {
        if (videoState.screen.sending) return
        updateVideoState(videoState.captureStopped(callId))
        updateCapturingVideoCall(callId, enabled = false)
    }

    @Synchronized
    fun onCameraCaptureStopped(callId: String) {
        if (videoState.screen.sending) return
        updateVideoState(videoState.captureStopped(callId))
        updateCapturingVideoCall(callId, enabled = false)
    }

    @Synchronized
    fun onCameraFacingChanged(callId: String, facing: CameraFacing) {
        updateVideoState(videoState.withFacing(callId, facing))
    }

    @Synchronized
    fun onCameraFailure(callId: String, message: String) {
        cameraFailed(callId, message)
    }

    @Synchronized
    fun onNetworkChanged() {
        session?.onNetworkChanged()
    }

    @Synchronized
    fun onMediaConnection(callId: String, epoch: Long, state: MediaConnectionState) {
        val previousGate = weakNetworkVideoGate.snapshot()
        val refreshScreen = state == MediaConnectionState.Connected && previousGate.callId == callId &&
            previousGate.transportReady && epoch > previousGate.epoch && videoState.screen.sending
        val refreshActiveVideo = state == MediaConnectionState.Connected &&
            previousGate.callId == callId &&
            previousGate.transportReady &&
            epoch > previousGate.epoch &&
            videoState.callId == callId &&
            videoState.sending
        val gate = when (state) {
            MediaConnectionState.Connected -> weakNetworkVideoGate.onTransportConnected(callId, epoch)
            MediaConnectionState.Connecting -> weakNetworkVideoGate.snapshot()
            MediaConnectionState.Disconnected,
            MediaConnectionState.Failed,
            MediaConnectionState.Closed -> weakNetworkVideoGate.onTransportUnavailable(callId, epoch)
        }
        applyWeakNetworkGate(callId, gate)
        if (refreshActiveVideo) refreshVideoSender(callId, epoch)
        if (refreshScreen) (session as? ScreenMediaSession)?.refreshScreenSender()
    }

    /** PeerConnection state includes DTLS; ICE connectivity alone is insufficient. */
    @Synchronized
    fun onTransportConnection(callId: String, state: MediaConnectionState) {
        val handshake = sasHandshake?.takeIf { sasParties?.callId == callId } ?: return
        when (state) {
            MediaConnectionState.Connected -> handshake.onTransportConnected()
            MediaConnectionState.Failed, MediaConnectionState.Closed ->
                handshake.reject(CallSecurityFailureReason.TransportFailed)
            MediaConnectionState.Connecting, MediaConnectionState.Disconnected -> handshake.onTransportUnavailable()
        }
    }

    @Synchronized
    fun getStats(onResult: (CallStats) -> Unit) {
        val current = session ?: return
        current.getStats { stats ->
            if (isCurrentSession(current)) onResult(stats)
        }
    }

    @Synchronized
    fun close(onClosed: () -> Unit = {}) {
        if (closed) {
            onClosed()
            return
        }
        closeWaiters += onClosed
        if (closing) return
        closing = true
        stopScreen(videoState.callId)
        cameraTransitionGeneration++
        credentialRefreshTask?.cancel()
        credentialRefreshTask = null
        iceRetryTask?.cancel()
        iceRetryTask = null
        iceRetryAtMillis = null
        restartRetryTask?.cancel()
        restartRetryTask = null
        restartRequestRetryTask?.cancel()
        restartRequestRetryTask = null
        scheduler.close()
        resetSAS()
        sasParties = null
        val media = session
        session = null
        callId = null
        iceServers = emptyList()
        videoAllowed = false
        configuredCallId = null
        acceptedCallId = null
        offerStartedCallId = null
        offerAwaitingAnswerCallId = null
        restartInFlightCallId = null
        restartAgainCallId = null
        restartRequestedCallId = null
        restartRequestID = null
        pendingRestart = null
        pendingRestartRequest = null
        localIceGeneration = null
        remoteIceGeneration = null
        localCandidateGenerations.clear()
        pendingOffer = null
        pendingIce.clear()
        nextLocalIceSequence = 0L
        recentLocalIceEvents.clear()
        rateLimitedIceEvents.clear()
        active = false
        muted = false
        capturingVideoCallId = null
        foregroundCallId = null
        cameraStartBlocked = false
        cameraStartSubmitted = false
        weakNetworkVideoGate.reset(null)
        updateVideoState(CallVideoState())
        requestCameraStop(
            target = media,
            afterDetached = {
                runCatching { if (media != null) runBlockingLite { media.close() } }
            },
            afterReleased = {
                closed = true
                closing = false
                val waiters = closeWaiters.toList()
                closeWaiters.clear()
                waiters.forEach { waiter -> runCatching(waiter) }
            },
        )
    }

    private fun handleRtcConfig(event: SignalEvent) {
        val nextIceServers = event.payload.parseIceServers()
        val nextVideoAllowed = event.payload["video_allowed"]
            ?.takeUnless { it.isJsonNull }
            ?.asBoolean == true
        val previousVideoCallId = videoState.callId
        val requiresCameraStop = previousVideoCallId != null &&
            (previousVideoCallId != event.callId || videoState.allowed && !nextVideoAllowed)
        if (!requiresCameraStop) {
            applyRtcConfig(event, nextIceServers, nextVideoAllowed)
            return
        }

        cameraStartSubmitted = false
        updateVideoState(
            videoState
                .withLocalTrack(requireNotNull(previousVideoCallId), null)
                .captureStopped(previousVideoCallId),
        )
        val transition = ++cameraTransitionGeneration
        val previousSession = session
        requestCameraStop(previousSession, afterDetached = {
            if (closing || transition != cameraTransitionGeneration) return@requestCameraStop
            applyRtcConfig(event, nextIceServers, nextVideoAllowed)
        })
    }

    private fun applyRtcConfig(
        event: SignalEvent,
        nextIceServers: List<IceServerData>,
        nextVideoAllowed: Boolean,
    ) {
        iceServers = nextIceServers
        videoAllowed = nextVideoAllowed
        if (weakNetworkVideoGate.snapshot().callId != event.callId) {
            weakNetworkVideoGate.reset(event.callId)
        }
        updateVideoState(
            videoState
                .configured(accountId, event.callId, videoAllowed)
                .let { it.copy(screen = it.screen.copy(allowed = event.payload["screen_sharing_allowed"]?.asBoolean == true)) }
                .withNetworkGate(event.callId, weakNetworkVideoGate.snapshot().networkGated),
        )
        configuredCallId = event.callId
        configureSAS(event.callId, event.payload)
        event.payload.restartID()?.let { localIceGeneration = it }
        session?.takeIf { callId == event.callId }?.let { media ->
            runBlockingLite { media.updateIceServers(iceServers) }
        }
        if (restartRequestedCallId == event.callId && restartRequestID == event.payload.restartID()) {
            restartRetryTask?.cancel()
            restartRetryTask = null
            val offer = runBlockingLite { ensureSession(event.callId).restartIce() }
            restartRequestedCallId = null
            restartRequestID = null
            pendingRestart = null
            offerAwaitingAnswerCallId = event.callId
            sasHandshake?.takeIf { sasParties?.callId == event.callId }?.recordLocalSdp(offer)
            sendSdp(event.callId, "rtc.offer", offer)
        } else {
            startOfferWhenReady(event.callId)
        }
        scheduleCredentialRefresh(event.callId)
        pendingOffer?.takeIf { it.callId == event.callId }?.let {
            pendingOffer = null
            answerOffer(it)
        }
    }

    private fun startOfferWhenReady(nextCallId: String) {
        if (acceptedCallId != nextCallId || configuredCallId != nextCallId || offerStartedCallId == nextCallId) return
        offerStartedCallId = nextCallId
        val offer = runBlockingLite { ensureSession(nextCallId).createOffer() }
        offerAwaitingAnswerCallId = nextCallId
        sasHandshake?.takeIf { sasParties?.callId == nextCallId }?.recordLocalSdp(offer)
        sendSdp(nextCallId, "rtc.offer", offer)
    }

    private fun answerOffer(event: SignalEvent) {
        val remoteSdp = event.payload["sdp"].asString
        sasHandshake?.takeIf { sasParties?.callId == event.callId }?.recordRemoteSdp(remoteSdp)
        val answer = runBlockingLite { ensureSession(event.callId).acceptOffer(remoteSdp) }
        sasHandshake?.takeIf { sasParties?.callId == event.callId }?.recordLocalSdp(answer)
        sendSdp(event.callId, "rtc.answer", answer)
        completeRemoteOffer(event.callId)
    }

    private fun completeLocalOffer(nextCallId: String) {
        if (offerAwaitingAnswerCallId != nextCallId) return
        offerAwaitingAnswerCallId = null
        if (restartInFlightCallId == nextCallId) restartInFlightCallId = null
        runDeferredRestart(nextCallId)
    }

    private fun completeRemoteOffer(nextCallId: String) {
        if (offerStartedCallId == nextCallId || restartInFlightCallId != nextCallId) return
        restartInFlightCallId = null
        runDeferredRestart(nextCallId)
    }

    private fun runDeferredRestart(nextCallId: String) {
        if (restartAgainCallId != nextCallId) return
        restartAgainCallId = null
        restartIce(nextCallId)
    }

    private fun ensureSession(nextCallId: String): MediaSession {
        val current = session
        if (current != null && callId == nextCallId) return current
        if (current != null) {
            runBlockingLite { current.close() }
            session = null
            callId = null
        }
        callId = nextCallId
        val created = mediaFactory(
            nextCallId,
            videoAllowed,
            iceServers,
            { candidate -> sendIce(nextCallId, candidate) },
            { candidates -> sendIceCandidatesRemoved(nextCallId, candidates) },
            { restartIce(nextCallId) },
        )
        session = created
        created.setActive(active)
        created.setMuted(muted)
        val queued = pendingIce.filter { it.first == nextCallId }.map { it.second }
        pendingIce.removeAll { it.first == nextCallId }
        if (queued.isNotEmpty()) runBlockingLite {
            queued.forEach { created.addIceCandidate(it) }
        }
        return created
    }

    private fun reconcileCamera(nextCallId: String) {
        if (
            !cameraEligible(nextCallId) ||
            cameraStartBlocked ||
            cameraStartSubmitted ||
            cameraDetachPending ||
            cameraReleasePending ||
            videoState.sending
        ) return
        val camera = session as? CameraMediaSession ?: return
        val lease = ++nextCameraLease
        if (!prepareCameraStart(nextCallId, lease)) {
            cameraFailed(nextCallId, "camera foreground service failed")
            return
        }
        activeCameraLease = lease
        cameraStartSubmitted = true
        try {
            camera.startCamera()
        } catch (failure: Throwable) {
            cameraStartSubmitted = false
            cameraFailed(nextCallId, failure.message ?: "camera start failed")
        }
    }

    private fun cameraEligible(nextCallId: String): Boolean =
        videoState.callId == nextCallId &&
            !videoState.screen.active && !screenStopping &&
            videoState.allowed &&
            videoState.requested &&
            videoState.permissionGranted &&
            !videoState.networkGated &&
            foregroundCallId == nextCallId

    private fun applyWeakNetworkGate(callId: String, gate: WeakNetworkVideoGateState) {
        if (gate.callId != callId || videoState.callId != callId) return
        val wasGated = videoState.networkGated
        if (wasGated == gate.networkGated) return
        updateVideoState(videoState.withNetworkGate(callId, gate.networkGated))
        if (videoState.screen.requested) {
            (session as? ScreenMediaSession)?.setScreenPaused(gate.networkGated)
            return
        }
        val cameraActiveOrStarting = videoState.sending || cameraStartSubmitted || videoState.localTrack != null
        if (gate.networkGated && cameraActiveOrStarting) {
            pauseCamera(callId)
        } else {
            reconcileCamera(callId)
        }
    }

    private fun refreshVideoSender(nextCallId: String, epoch: Long) {
        if (videoState.callId != nextCallId || !videoState.sending) return
        val camera = session as? CameraMediaSession ?: return
        runCatching {
            camera.refreshVideoSender {
                videoSenderRefreshFailed(nextCallId, epoch)
            }
        }
    }

    @Synchronized
    private fun videoSenderRefreshFailed(nextCallId: String, epoch: Long) {
        val gate = weakNetworkVideoGate.snapshot()
        if (
            gate.callId != nextCallId || gate.epoch != epoch || !gate.transportReady ||
            videoState.callId != nextCallId || !videoState.sending
        ) return
        pauseCamera(nextCallId)
    }

    private fun requestCameraStop(
        target: MediaSession?,
        usePause: Boolean = false,
        afterDetached: () -> Unit = {},
        afterReleased: () -> Unit = {},
    ) {
        if (cameraReleasePending) {
            cameraReleaseWaiters += afterReleased
            if (cameraDetachPending) cameraDetachWaiters += afterDetached else afterDetached()
            return
        }
        cameraDetachWaiters += afterDetached
        cameraReleaseWaiters += afterReleased
        val retirement = ++nextCameraRetirement
        cameraRetirement = retirement
        cameraDetachPending = true
        cameraReleasePending = true
        cameraReleaseFailed = false
        cameraStartSubmitted = false
        val lease = activeCameraLease
        activeCameraLease = null
        val camera = target as? CameraMediaSession
        if (camera == null) {
            completeCameraDetach(retirement)
            completeCameraRelease(retirement, lease)
            return
        }
        runCatching {
            val detached = { completeCameraDetach(retirement) }
            val released = { completeCameraRelease(retirement, lease) }
            if (usePause) camera.pauseCamera(detached, released) else camera.stopCamera(detached, released)
        }.onFailure {
            cameraReleaseFailed = true
            completeCameraRelease(retirement, lease)
        }
    }

    @Synchronized
    private fun completeCameraDetach(retirement: Long) {
        if (cameraRetirement != retirement || !cameraDetachPending) return
        cameraDetachPending = false
        val waiters = cameraDetachWaiters.toList()
        cameraDetachWaiters.clear()
        waiters.forEach { it() }
    }

    @Synchronized
    private fun completeCameraRelease(retirement: Long, lease: Long?) {
        if (cameraRetirement != retirement || !cameraReleasePending) return
        if (cameraDetachPending) completeCameraDetach(retirement)
        cameraReleasePending = false
        lease?.let(onCameraLeaseReleased)
        val waiters = cameraReleaseWaiters.toList()
        cameraReleaseWaiters.clear()
        waiters.forEach { it() }
        if (!closing) videoState.callId?.let(::reconcileCamera)
    }

    private fun pauseCamera(nextCallId: String) {
        if (videoState.callId != nextCallId) return
        if (videoState.screen.requested) return
        cameraStartSubmitted = false
        updateVideoState(videoState.withLocalTrack(nextCallId, null).captureStopped(nextCallId))
        try {
            requestCameraStop(session, usePause = true)
        } catch (failure: Throwable) {
            cameraFailed(nextCallId, failure.message ?: "camera pause failed")
        }
    }

    private fun cameraFailed(nextCallId: String, message: String) {
        if (videoState.callId != nextCallId) return
        if (videoState.screen.active || screenStopping) return
        cameraStartBlocked = true
        cameraStartSubmitted = false
        updateVideoState(videoState.failed(nextCallId, message))
        requestCameraStop(session)
    }

    private fun updateVideoState(next: CallVideoState<VideoRenderSource>) {
        if (next == videoState) return
        if (videoState.localTrack !== next.localTrack) videoState.localTrack?.close()
        if (videoState.remoteTrack !== next.remoteTrack) videoState.remoteTrack?.close()
        videoState = next
        onVideoStateChanged(next)
    }

    private fun updateCapturingVideoCall(callbackCallId: String, enabled: Boolean) {
        if (enabled) {
            if (capturingVideoCallId == callbackCallId) return
            if (videoState.callId != callbackCallId || !videoState.allowed) return
            capturingVideoCallId = callbackCallId
        } else {
            if (capturingVideoCallId != callbackCallId) return
            capturingVideoCallId = null
        }
        sendVideoState(callbackCallId, enabled)
    }

    @Synchronized
    private fun isCurrentSession(candidate: MediaSession): Boolean = session === candidate

    @Synchronized
    fun onSignalConnected() {
        if (restartRetryTask == null) pendingRestart?.let { signal.send(it) }
        if (restartRequestRetryTask == null) pendingRestartRequest?.let { signal.send(it) }
        sendVideoState(videoState.callId, capturingVideoCallId == videoState.callId)
    }

    private fun sendVideoState(nextCallId: String?, enabled: Boolean) {
        val currentCallId = callId ?: return
        if (
            closing || closed ||
            nextCallId != currentCallId ||
            configuredCallId != currentCallId ||
            !videoAllowed || !videoState.allowed
        ) return
        signal.send(
            event(
                currentCallId,
                "rtc.video",
                JsonObject().apply { addProperty("enabled", enabled || videoState.screen.sending) },
            ),
        )
    }

    @Synchronized
    fun onSignalFailure(failure: SignalFailure) {
        if (failure.code?.startsWith("call_sas_") == true) {
            if (failure.callId == sasParties?.callId && failure.callId != null) {
                val reason = if (failure.code == "call_sas_timeout") CallSecurityFailureReason.ExchangeTimeout
                    else CallSecurityFailureReason.UnexpectedMessage
                sasHandshake?.reject(reason)
            }
            return
        }
        if (failure.callId == videoState.callId && failure.eventId == screenStartEventId && screenStartEventId != null) {
            Log.w("TiniTalkScreen", "screen request rejected by server: ${failure.code}")
            stopScreen(failure.callId, if (failure.code == "screen_share_busy") "Собеседник уже показывает экран" else "Не удалось начать показ экрана")
            return
        }
        when (failure.code) {
            "ice_rate_limited" -> {
                val eventId = failure.eventId ?: return
                val rejected = recentLocalIceEvents[eventId] ?: return
                if (failure.callId != rejected.event.callId || callId != rejected.event.callId) return
                val delayMillis = failure.retryAfterMillis?.coerceAtLeast(1L) ?: return
                if (rateLimitedIceEvents.size == SignalEvent.EVENT_BUFFER_LIMIT && rejected.event.id !in rateLimitedIceEvents) {
                    rateLimitedIceEvents.remove(rateLimitedIceEvents.keys.first())
                }
                rateLimitedIceEvents[rejected.event.id] = rejected
                val retryAtMillis = ids.nowMillis() + delayMillis
                if (iceRetryAtMillis?.let { it >= retryAtMillis } == true) return
                iceRetryTask?.cancel()
                iceRetryAtMillis = retryAtMillis
                iceRetryTask = scheduler.schedule(delayMillis) {
                    synchronized(this) {
                        iceRetryTask = null
                        iceRetryAtMillis = null
                        val pending = rateLimitedIceEvents.values
                            .filter { it.event.callId == callId }
                            .sortedBy(LocalIceEvent::sequence)
                            .map(LocalIceEvent::event)
                        rateLimitedIceEvents.clear()
                        pending.forEach { signal.send(it) }
                    }
                }
            }
            "ice_restart_rate_limited" -> {
                val restart = pendingRestart ?: return
                if (failure.callId != restart.callId || failure.eventId != restart.id) return
                val delayMillis = failure.retryAfterMillis?.coerceAtLeast(1L) ?: return
                restartRetryTask?.cancel()
                restartRetryTask = scheduler.schedule(delayMillis) {
                    synchronized(this) {
                        restartRetryTask = null
                        val pending = pendingRestart
                        if (pending?.id == restart.id && callId == restart.callId) signal.send(pending)
                    }
                }
            }
            "ice_restart_request_rate_limited" -> {
                val request = pendingRestartRequest ?: return
                if (failure.callId != request.callId || failure.eventId != request.id) return
                val delayMillis = failure.retryAfterMillis?.coerceAtLeast(1L) ?: return
                restartRequestRetryTask?.cancel()
                restartRequestRetryTask = scheduler.schedule(delayMillis) {
                    synchronized(this) {
                        restartRequestRetryTask = null
                        val pending = pendingRestartRequest
                        if (pending?.id == request.id && callId == request.callId) signal.send(pending)
                    }
                }
            }
        }
    }

    @Synchronized
    fun requestScreen(nextCallId: String, permission: Intent) {
        if (closing || videoState.callId != nextCallId || !videoState.screen.allowed ||
            videoState.screen.requested || screenStopping || videoState.screen.remoteId != null ||
            !weakNetworkVideoGate.snapshot().transportReady || session !is ScreenMediaSession) return
        val shareId = ids.nextEventId()
        screenPermission = permission
        updateVideoState(videoState.copy(screen = videoState.screen.copy(localId = shareId, failure = null)))
        val start = event(nextCallId, "rtc.screen", JsonObject().apply {
            addProperty("enabled", true); addProperty("share_id", shareId)
        })
        screenStartEventId = start.id
        screenTimeout = scheduler.schedule(15_000) {
            synchronized(this) {
                if (videoState.screen.localId == shareId && !videoState.screen.sending) {
                    Log.w("TiniTalkScreen", "screen start timed out: cameraReady=${screenCameraReadyId == shareId} " +
                        "serverReady=${videoState.screen.ready} captureSubmitted=$screenStartSubmitted")
                    stopScreen(nextCallId, "Не удалось начать показ экрана. Попробуйте ещё раз.")
                }
            }
        }
        signal.send(start)
    }

    private fun onScreenState(event: SignalEvent) {
        if (videoState.callId != event.callId || !videoState.screen.allowed || closing) return
        val presenter = event.payload["presenter_id"]?.asString.orEmpty()
        val shareId = event.payload["share_id"]?.asString ?: return
        if (presenter.isEmpty()) {
            val wasPreparing = screenPreparationId != null
            if (videoState.screen.requested && wasPreparing && !videoState.screen.sending) {
                Log.w("TiniTalkScreen", "server cleared screen preparation: cameraReady=${screenCameraReadyId == screenPreparationId} " +
                    "serverReady=${videoState.screen.ready} captureSubmitted=$screenStartSubmitted")
            }
            screenPreparationId = null
            screenCameraReadyId = null
            updateVideoState(videoState.copy(
                remoteSending = if (wasPreparing) false else videoState.remoteSending,
                screen = videoState.screen.copy(remoteId = null, ready = false),
            ))
            if (videoState.screen.requested && wasPreparing) {
                stopScreen(event.callId, if (videoState.screen.sending) null else "Не удалось начать показ экрана")
            }
            return
        }
        if (presenter == selfLogin && videoState.screen.localId != shareId) {
            sendScreenStop(event.callId, shareId) // A replay is not consent to capture again.
            return
        }
        val remote = shareId.takeIf { presenter.isNotEmpty() && presenter != selfLogin }
        updateVideoState(videoState.copy(screen = videoState.screen.copy(
            remoteId = remote, ready = event.payload["ready"]?.asBoolean == true,
        )))
        if (remote != null && videoState.screen.requested) {
            stopScreen(event.callId, "Собеседник уже показывает экран")
        }
        if (screenPreparationId != shareId) {
            screenPreparationId = shareId
            screenCameraReadyId = null
            updateVideoState(videoState.manualOff(event.callId).copy(remoteSending = false))
            updateCapturingVideoCall(event.callId, enabled = false)
            requestCameraStop(session, afterReleased = {
                if (closing || videoState.callId != event.callId || screenPreparationId != shareId ||
                    cameraReleaseFailed) return@requestCameraStop
                screenCameraReadyId = shareId
                sendScreenReady(event.callId, shareId)
                startPreparedScreen(event.callId, shareId)
            })
        } else if (screenCameraReadyId == shareId && !videoState.screen.ready) {
            // Repeat after resume if the previous acknowledgement never reached the server.
            sendScreenReady(event.callId, shareId)
        }
        startPreparedScreen(event.callId, shareId)
    }

    private fun sendScreenReady(nextCallId: String, shareId: String) {
        signal.send(event(nextCallId, "rtc.screen.ready", JsonObject().apply { addProperty("share_id", shareId) }))
    }

    private fun startPreparedScreen(nextCallId: String, shareId: String) {
        if (closing || videoState.screen.localId != shareId || !videoState.screen.ready ||
            screenCameraReadyId != shareId || screenStartSubmitted) return
        val permission = screenPermission ?: return
        screenPermission = null
        screenStartSubmitted = true
        try {
            if (!prepareScreenStart(nextCallId)) {
                Log.w("TiniTalkScreen", "screen foreground preparation failed")
                stopScreen(nextCallId, "Не удалось начать показ экрана")
                return
            }
            val screen = session as? ScreenMediaSession ?: error("screen session missing")
            screen.startScreen(permission,
                onStarted = { onScreenStarted(nextCallId, shareId) },
                onStopped = { message -> onScreenStopped(nextCallId, shareId, message) },
            )
            screen.setScreenPaused(videoState.networkGated)
        } catch (failure: Exception) {
            Log.e("TiniTalkScreen", "screen session start failed", failure)
            stopScreen(nextCallId, "Не удалось начать показ экрана")
        }
    }

    @Synchronized
    private fun onScreenStarted(nextCallId: String, shareId: String) {
        if (closing || videoState.callId != nextCallId || videoState.screen.localId != shareId) return
        Log.i("TiniTalkScreen", "first screen frame captured")
        screenTimeout?.cancel(); screenTimeout = null
        updateVideoState(videoState.copy(screen = videoState.screen.copy(sending = true)))
        sendVideoState(nextCallId, true)
    }

    @Synchronized
    private fun onScreenStopped(nextCallId: String, shareId: String, message: String?) {
        if (videoState.callId == nextCallId && videoState.screen.localId == shareId) stopScreen(nextCallId, message)
    }

    @Synchronized
    fun stopScreen(nextCallId: String?, message: String? = null) {
        if (videoState.callId != nextCallId || screenStopping) return
        val shareId = videoState.screen.localId ?: return
        screenStopping = true
        screenTimeout?.cancel(); screenTimeout = null
        screenPermission = null
        screenStartSubmitted = false
        screenStartEventId = null
        if (screenPreparationId == shareId) {
            screenPreparationId = null
            screenCameraReadyId = null
        }
        updateVideoState(videoState.copy(screen = videoState.screen.copy(localId = null, sending = false, failure = message)))
        sendVideoState(nextCallId, videoState.sending)
        if (nextCallId != null) sendScreenStop(nextCallId, shareId)
        val stopped = {
            synchronized(this) {
                screenStopping = false
                onScreenReleased()
            }
        }
        (session as? ScreenMediaSession)?.stopScreen(stopped) ?: stopped()
    }

    private fun sendScreenStop(nextCallId: String, shareId: String) {
        if (closed) return
        signal.send(event(nextCallId, "rtc.screen", JsonObject().apply {
            addProperty("enabled", false); addProperty("share_id", shareId)
        }))
    }

    @Synchronized
    private fun restartIce(nextCallId: String) {
        if (callId != nextCallId) return
        if (offerAwaitingAnswerCallId == nextCallId || restartInFlightCallId == nextCallId) {
            restartAgainCallId = nextCallId
            return
        }
        restartInFlightCallId = nextCallId
        if (offerStartedCallId != nextCallId) {
            event(nextCallId, "rtc.restart.request", JsonObject()).also {
                pendingRestartRequest = it
                signal.send(it)
            }
            return
        }
        val restart = event(nextCallId, "rtc.restart", JsonObject())
        restartRequestedCallId = nextCallId
        restartRequestID = restart.id
        pendingRestart = restart
        remoteIceGeneration = restart.id
        signal.send(restart)
    }

    private fun clearPendingRestartRequest(nextCallId: String) {
        if (pendingRestartRequest?.callId != nextCallId) return
        restartRequestRetryTask?.cancel()
        restartRequestRetryTask = null
        pendingRestartRequest = null
    }

    private fun scheduleCredentialRefresh(nextCallId: String) {
        credentialRefreshTask?.cancel()
        credentialRefreshTask = null
        if (acceptedCallId != nextCallId) return
        val expiresAt = iceServers.mapNotNull { it.expiresAt }.minOrNull() ?: return
        val delayMillis = (expiresAt.toEpochMilli() - ids.nowMillis() - CredentialRefreshLeadMillis).coerceAtLeast(0L)
        credentialRefreshTask = scheduler.schedule(delayMillis) { restartIce(nextCallId) }
    }

    private fun JsonObject.parseIceServers(): List<IceServerData> {
        val servers = getAsJsonArray("ice_servers") ?: return emptyList()
        return servers.mapNotNull { element ->
            val server = element.asJsonObject
            val urls = server.getAsJsonArray("urls")?.map { it.asString } ?: return@mapNotNull null
            IceServerData(
                urls = urls,
                username = server.get("username")?.asString.orEmpty(),
                password = server.get("credential")?.asString.orEmpty(),
                expiresAt = runCatching {
                    server.get("expires_at")
                        ?.takeIf { it.isJsonPrimitive }
                        ?.asString
                        ?.let { value -> Instant.parse(value) }
                }.getOrNull(),
            )
        }
    }

    private fun JsonObject.restartID(): String? =
        get("restart_id")?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.isIceRemoval(): Boolean =
        get("removed")?.takeIf { it.isJsonPrimitive }?.asBoolean == true

    private fun JsonObject.parseIceCandidates(): List<IceCandidateData> =
        getAsJsonArray("candidates")?.mapNotNull { element ->
            val candidate = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val sdp = candidate.get("candidate")?.takeIf { it.isJsonPrimitive }?.asString ?: return@mapNotNull null
            IceCandidateData(
                sdpMid = candidate.get("sdp_mid")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
                sdpMLineIndex = candidate.get("sdp_mline_index")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0,
                candidate = sdp,
            )
        }.orEmpty()

    private fun removeIceCandidates(nextCallId: String, candidates: List<IceCandidateData>) {
        if (candidates.isEmpty()) return
        val pendingForCall = pendingIce.filter { it.first == nextCallId && it.second in candidates }.toSet()
        pendingIce.removeAll(pendingForCall)
        val media = session
        if (media != null && callId == nextCallId) {
            val applied = candidates.filterNot { candidate -> pendingForCall.any { it.second == candidate } }
            if (applied.isNotEmpty()) runBlockingLite { media.removeIceCandidates(applied) }
        }
    }

    private fun sendSdp(callId: String, type: String, sdp: String) {
        val payload = JsonObject().apply { addProperty("sdp", sdp) }
        signal.send(event(callId, type, payload))
    }

    private fun alignSASRole(nextCallId: String, localIsCaller: Boolean, crossed: Boolean) {
        val current = sasParties ?: return
        if (current.callId != nextCallId && !crossed) return
        if (current.callId == nextCallId && current.localIsCaller == localIsCaller) return
        if (sasState != null || sasHandshake != null) {
            sasHandshake?.reject(CallSecurityFailureReason.UnexpectedMessage)
            return
        }
        sasParties = current.copy(
            callId = nextCallId,
            caller = if (current.localIsCaller == localIsCaller) current.caller else current.callee,
            callee = if (current.localIsCaller == localIsCaller) current.callee else current.caller,
            localIsCaller = localIsCaller,
        )
    }

    private fun configureSAS(nextCallId: String, payload: JsonObject) {
        if (sasState is CallSecurityState.Failed || sasState is CallSecurityState.Unavailable) return
        val allowed = payload["call_sas_allowed"]
        if (sasHandshake != null) {
            if (allowed == null || !allowed.isJsonPrimitive || !allowed.asJsonPrimitive.isBoolean || !allowed.asBoolean) {
                sasHandshake?.reject(CallSecurityFailureReason.UnexpectedMessage)
            }
            return
        }
        if (allowed == null) {
            publishSASUnavailable(nextCallId, CallSecurityUnavailableReason.ServerUnsupported)
            return
        }
        if (!allowed.isJsonPrimitive || !allowed.asJsonPrimitive.isBoolean) {
            resetSAS()
            publishSAS(
                nextCallId,
                CallSecurityState.Failed(CallSecurityFailureReason.UnexpectedMessage),
            )
            return
        }
        if (!allowed.asBoolean) {
            publishSASUnavailable(nextCallId, CallSecurityUnavailableReason.PeerUnsupported)
            return
        }
        val parties = sasParties?.takeIf { it.callId == nextCallId }
        if (parties == null) {
            publishSAS(
                nextCallId,
                CallSecurityState.Failed(CallSecurityFailureReason.InternalError),
            )
            return
        }
        sasHandshake = CallSASHandshake(
            callId = nextCallId,
            caller = parties.caller,
            callee = parties.callee,
            role = if (parties.localIsCaller) CallSASRole.Caller else CallSASRole.Callee,
            send = { type, payload -> signal.send(event(nextCallId, type, payload)) },
            publish = { state -> publishSAS(nextCallId, state) },
        )
        scheduleSASTimeout()
    }

    private fun scheduleSASTimeout() {
        if (sasTimeoutTask != null) return
        val handshake = sasHandshake ?: return
        sasTimeoutTask = scheduler.schedule(SecurityCodeTimeoutMillis) {
            synchronized(this) { if (sasHandshake === handshake) handshake.timeOut() }
        }
    }

    private fun publishSAS(nextCallId: String, state: CallSecurityState) {
        if (sasState is CallSecurityState.Failed) return
        sasState = state
        if (state == CallSecurityState.Establishing) {
            scheduleSASTimeout()
        } else {
            sasTimeoutTask?.cancel()
            sasTimeoutTask = null
        }
        onSecurityStateChanged(nextCallId, state)
    }

    private fun publishSASUnavailable(nextCallId: String, reason: CallSecurityUnavailableReason) {
        if (sasParties?.callId != nextCallId) return
        resetSAS()
        publishSAS(nextCallId, CallSecurityState.Unavailable(reason))
    }

    private fun resetSAS() {
        sasTimeoutTask?.cancel()
        sasTimeoutTask = null
        sasHandshake?.close()
        sasHandshake = null
    }

    @Synchronized
    private fun sendIce(callId: String, candidate: IceCandidateData) {
        if (this.callId != callId) return
        val generation = localIceGeneration
        localCandidateGenerations[candidate] = generation
        val payload = JsonObject().apply {
            addProperty("sdp_mid", candidate.sdpMid)
            addProperty("sdp_mline_index", candidate.sdpMLineIndex)
            addProperty("candidate", candidate.candidate)
            generation?.let { addProperty("restart_id", it) }
        }
        sendLocalIceEvent(event(callId, "rtc.ice", payload))
    }

    @Synchronized
    private fun sendIceCandidatesRemoved(callId: String, candidates: List<IceCandidateData>) {
        if (this.callId != callId) return
        candidates.groupBy { candidate ->
            if (localCandidateGenerations.containsKey(candidate)) {
                localCandidateGenerations.remove(candidate)
            } else {
                localIceGeneration
            }
        }.forEach { (generation, generationCandidates) ->
            sendIceRemovalBatches(callId, generation, generationCandidates)
        }
    }

    private fun sendIceRemovalBatches(callId: String, generation: String?, candidates: List<IceCandidateData>) {
        val batch = mutableListOf<IceCandidateData>()
        candidates.forEach { candidate ->
            val expanded = batch + candidate
            if (iceRemovalFits(callId, generation, expanded)) {
                batch += candidate
            } else {
                sendIceRemovalBatch(callId, generation, batch)
                batch.clear()
                if (iceRemovalFits(callId, generation, listOf(candidate))) batch += candidate
            }
        }
        sendIceRemovalBatch(callId, generation, batch)
    }

    private fun sendIceRemovalBatch(callId: String, generation: String?, candidates: List<IceCandidateData>) {
        if (candidates.isEmpty()) return
        sendLocalIceEvent(event(callId, "rtc.ice", iceRemovalPayload(candidates, generation)))
    }

    private fun sendLocalIceEvent(event: SignalEvent) {
        if (recentLocalIceEvents.size == SignalEvent.EVENT_BUFFER_LIMIT) {
            recentLocalIceEvents.remove(recentLocalIceEvents.keys.first())
        }
        recentLocalIceEvents[event.id] = LocalIceEvent(nextLocalIceSequence++, event)
        signal.send(event)
    }

    private fun iceRemovalFits(callId: String, generation: String?, candidates: List<IceCandidateData>): Boolean =
        runCatching {
            SignalEvent(
                ICE_EVENT_SIZE_PROBE_ID,
                callId,
                "rtc.ice",
                ids.nowMillis(),
                iceRemovalPayload(candidates, generation),
            ).encode()
        }.isSuccess

    private fun iceRemovalPayload(candidates: List<IceCandidateData>, generation: String?): JsonObject {
        val first = candidates.first()
        return JsonObject().apply {
            addProperty("removed", true)
            add("candidates", com.google.gson.JsonArray().apply {
                candidates.forEach { add(it.toJson()) }
            })
            addProperty("sdp_mid", first.sdpMid)
            addProperty("sdp_mline_index", first.sdpMLineIndex)
            addProperty("candidate", first.candidate)
            generation?.let { addProperty("restart_id", it) }
        }
    }

    private fun IceCandidateData.toJson() = JsonObject().apply {
        addProperty("sdp_mid", sdpMid)
        addProperty("sdp_mline_index", sdpMLineIndex)
        addProperty("candidate", candidate)
    }

    private fun event(callId: String, type: String, payload: JsonObject): SignalEvent =
        SignalEvent(ids.nextEventId(), callId, type, ids.nowMillis(), payload)

    private fun <T> runBlockingLite(block: suspend () -> T): T {
        val done = CountDownLatch(1)
        var value: T? = null
        var failure: Throwable? = null
        block.startCoroutine(
            object : Continuation<T> {
                override val context = EmptyCoroutineContext
                override fun resumeWith(result: Result<T>) {
                    value = result.getOrNull()
                    failure = result.exceptionOrNull()
                    done.countDown()
                }
            },
        )
        check(done.await(10, TimeUnit.SECONDS)) { "Timed out waiting for media session" }
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    private companion object {
        const val ICE_EVENT_SIZE_PROBE_ID = "00000000-0000-0000-0000-000000000000"
    }
}
