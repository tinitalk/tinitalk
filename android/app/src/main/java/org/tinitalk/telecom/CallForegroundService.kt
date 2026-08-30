package org.tinitalk.telecom

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.tinitalk.BuildConfig
import org.tinitalk.CallActivity
import org.tinitalk.R
import org.tinitalk.call.CallCoordinator
import org.tinitalk.call.CallAudioState
import org.tinitalk.call.CallDirection
import org.tinitalk.call.CallEndReason
import org.tinitalk.call.CallPhase
import org.tinitalk.call.CallPeer
import org.tinitalk.call.CallServiceState
import org.tinitalk.call.CallUiStateStore
import org.tinitalk.call.CallUiState
import org.tinitalk.call.ConnectionHealth
import org.tinitalk.call.VideoCallStateStore
import org.tinitalk.call.ForegroundCallController
import org.tinitalk.data.AndroidKeystoreTokenCipher
import org.tinitalk.data.AuthStore
import org.tinitalk.data.SessionReplacedReason
import org.tinitalk.data.SharedPreferencesKeyValueStore
import org.tinitalk.data.signal.SignalSocket
import org.tinitalk.data.signal.SignalFailure
import org.tinitalk.media.WebRtcCallSession
import org.tinitalk.media.CancellableTask
import org.tinitalk.media.ConnectionHealthClassifier
import org.tinitalk.media.DefaultNetworkObserver
import org.tinitalk.media.MediaConnectionState
import org.tinitalk.network.networkAvailability
import org.tinitalk.media.CameraMediaCallbacks
import org.tinitalk.media.CallMediaDispatcher
import org.tinitalk.push.DeviceIdentity
import org.tinitalk.push.IncomingCallNotifier
import okhttp3.OkHttpClient
import java.time.Instant
import java.util.concurrent.TimeUnit

internal fun signalingHttpClient(): OkHttpClient =
    OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

internal fun callNotificationIcon(state: CallUiState): Int = when {
    state.phase == CallPhase.Active && state.connectionHealth == ConnectionHealth.Reconnecting ->
        R.drawable.ic_call_reconnecting
    state.direction == CallDirection.Outgoing &&
        (state.phase == CallPhase.Ringing || state.phase == CallPhase.Connecting) ->
        R.drawable.ic_call_outgoing
    state.phase == CallPhase.Ringing -> R.drawable.ic_call_ringing
    else -> R.drawable.ic_call_active
}

internal fun migrateCallNetwork(
    reconnectSignaling: () -> Unit,
    restartMedia: () -> Unit,
) {
    reconnectSignaling()
    restartMedia()
}

internal fun postCurrentMediaCallback(
    post: (() -> Unit) -> Unit,
    isCurrent: () -> Boolean,
    onDropped: () -> Unit = {},
    action: () -> Unit,
) {
    post {
        if (isCurrent()) action() else onDropped()
    }
}

internal fun callForegroundServiceType(cameraSending: Boolean): Int =
    ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or
        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
        if (cameraSending) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0

internal sealed interface CameraCallAction {
    val callId: String

    data class Request(override val callId: String, val requested: Boolean) : CameraCallAction
    data class Foreground(
        override val callId: String,
        val foreground: Boolean,
        val permissionGranted: Boolean,
    ) : CameraCallAction
    data class Switch(override val callId: String) : CameraCallAction
}

private data class CameraForegroundLease(
    val owner: ForegroundCallController,
    val id: Long,
)

internal fun cameraCallAction(intent: Intent): CameraCallAction? {
    val callId = intent.getStringExtra(CallForegroundService.ExtraCallId) ?: return null
    return when (intent.action) {
        CallForegroundService.ActionCameraRequest -> CameraCallAction.Request(
            callId,
            intent.getBooleanExtra(CallForegroundService.ExtraCameraRequested, false),
        )
        CallForegroundService.ActionCameraForeground -> CameraCallAction.Foreground(
            callId,
            intent.getBooleanExtra(CallForegroundService.ExtraCameraForeground, false),
            intent.getBooleanExtra(CallForegroundService.ExtraCameraPermission, false),
        )
        CallForegroundService.ActionCameraSwitch -> CameraCallAction.Switch(callId)
        else -> null
    }
}

class CallForegroundService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var socket: SignalSocket? = null
    private var httpClient: OkHttpClient? = null
    private var coordinator: CallCoordinator? = null
    @Volatile private var media: ForegroundCallController? = null
    @Volatile private var mediaDispatcher: CallMediaDispatcher? = null
    @Volatile private var connected = false
    @Volatile private var finishing = false
    @Volatile private var callResourcesReleased = false
    private var statsPolling = false
    private val statsRequestGate = MediaStatsRequestGate()
    @Volatile private var statsSession: MediaStatsSession? = null
    @Volatile private var cameraForegroundTypeEnabled = false
    @Volatile private var cameraForegroundLease: CameraForegroundLease? = null
    @Volatile private var runtimeGeneration = 0L
    private var telecomCallId: String? = null
    private var outgoingPeer: CallPeer? = null
    private var callNetworkLock: CallNetworkLock? = null
    private var networkObserver: DefaultNetworkObserver? = null
    private val foregroundLock = Any()
    private lateinit var callTones: CallToneController
    private val connectionHealthClassifier = ConnectionHealthClassifier()
    private val terminalSignalGate = TerminalSignalGate(
        timeoutMillis = TerminalSignalTimeoutMillis,
        scheduleTimeout = { delayMillis, action ->
            handler.postDelayed(action, delayMillis)
            CancellableTask { handler.removeCallbacks(action) }
        },
    )
    private val callUiObserver: (CallUiState) -> Unit = { state ->
        handler.post {
            if (!finishing && !callResourcesReleased) {
                callTones.update(state)
                if (state.phase != CallPhase.Idle) {
                    getSystemService(NotificationManager::class.java).notify(NotificationId, notification(state))
                }
            }
        }
    }
    private val statsTask = object : Runnable {
        override fun run() {
            if (!statsPolling) return
            val activeCallId = CallServiceState.snapshot()
                .takeIf { it.phase == CallPhase.Active }
                ?.callId
            val currentMedia = media
            val currentDispatcher = mediaDispatcher
            val currentStatsSession = statsSession
            val request = if (
                activeCallId != null && currentMedia != null && currentDispatcher != null &&
                currentStatsSession?.callId == activeCallId
            ) {
                statsRequestGate.begin(currentStatsSession)
            } else {
                null
            }
            if (activeCallId != null && currentMedia != null && currentDispatcher != null && request != null) {
                val accepted = currentDispatcher.dispatch {
                    if (finishing || media !== currentMedia) {
                        statsRequestGate.complete(request)
                        return@dispatch
                    }
                    runCatching {
                        currentMedia.getStats { stats ->
                            handler.post {
                                val accepted = statsRequestGate.complete(request)
                                val snapshot = CallServiceState.snapshot()
                                val stillActive = statsPolling && !finishing &&
                                    snapshot.phase == CallPhase.Active && snapshot.callId == activeCallId
                                if (accepted && stillActive && media === currentMedia) {
                                    Log.i(CallLogTag, CallDiagnostics.format(stats))
                                    val currentHealth = CallUiStateStore.snapshot().connectionHealth
                                    val health = connectionHealthClassifier.update(stats, currentHealth)
                                    CallUiStateStore.setConnectionHealth(activeCallId, health)
                                }
                            }
                        }
                    }.onFailure {
                        statsRequestGate.complete(request)
                    }
                }
                if (!accepted) statsRequestGate.complete(request)
            }
            if (statsPolling) handler.postDelayed(this, CallDiagnostics.IntervalMillis)
        }
    }
    private val telecom by lazy { TelecomCallController(AndroidTelecomRegistrar(this)) }

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        callTones = CallToneController(handler)
        CallUiStateStore.observe(callUiObserver)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (rejectOfflineOutgoingStart(intent?.action, networkAvailability().canStartNetworkAction())) {
            val phase = coordinator?.snapshot()?.phase ?: CallServiceState.snapshot().phase
            if (phase == CallPhase.Idle || phase == CallPhase.Ended) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (finishing && callResourcesReleased && replacesTerminalCall(intent)) {
            resetReleasedRuntime()
        }
        if (finishing) {
            endSystemCall(intent?.getStringExtra(ExtraCallId))
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (callResourcesReleased) {
            if (replacesTerminalCall(intent)) {
                resetReleasedRuntime()
            } else if (terminalSignalGate.isWaiting()) {
                if (!satisfyTerminalForegroundStart()) {
                    finishing = true
                    terminalSignalGate.close()
                    endSystemCall(intent?.getStringExtra(ExtraCallId))
                    stopSelf(startId)
                }
            } else {
                finishing = true
                endSystemCall(intent?.getStringExtra(ExtraCallId))
                stopSelf(startId)
                return START_NOT_STICKY
            }
            if (callResourcesReleased) return START_NOT_STICKY
        }
        updateForegroundType(cameraSending = cameraForegroundTypeEnabled)
        if (intent == null || !ensureRuntime()) {
            finishing = true
            releaseCallResources(intent?.getStringExtra(ExtraCallId))
            stopSelf()
            return START_NOT_STICKY
        }
        handle(intent)
        return START_NOT_STICKY
    }

    private fun replacesTerminalCall(intent: Intent?): Boolean {
        if (intent?.action == ActionStart) return true
        val nextCallId = IncomingCallController.inviteFrom(intent)?.callId ?: return false
        val terminalCallId = coordinator?.snapshot()?.callId ?: CallUiStateStore.snapshot().callId
        return nextCallId != terminalCallId
    }

    private fun resetReleasedRuntime() {
        runtimeGeneration++
        terminalSignalGate.close()
        runCatching { callNetworkLock?.close() }
        callNetworkLock = null
        runCatching { networkObserver?.close() }
        networkObserver = null
        runCatching { socket?.close() }
        runCatching { httpClient?.dispatcher?.executorService?.shutdownNow() }
        runCatching { httpClient?.connectionPool?.evictAll() }
        runCatching { coordinator?.finish() }
        socket = null
        httpClient = null
        coordinator = null
        connected = false
        telecomCallId = null
        outgoingPeer = null
        callResourcesReleased = false
        finishing = false
        CallServiceState.reset()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        val unexpected = !finishing
        finishing = true
        runtimeGeneration++
        CallUiStateStore.removeObserver(callUiObserver)
        callTones.close()
        terminalSignalGate.close()
        stopStatsPolling()
        callNetworkLock?.close()
        callNetworkLock = null
        networkObserver?.close()
        networkObserver = null
        val snapshot = coordinator?.snapshot()
        if (unexpected && connected && snapshot?.phase == CallPhase.Active) {
            runCatching { coordinator?.hangUp() }
        }
        releaseCallResources(snapshot?.callId)
        runCatching { socket?.close() }
        runCatching { httpClient?.dispatcher?.executorService?.shutdownNow() }
        runCatching { httpClient?.connectionPool?.evictAll() }
        CallAudioState.reset()
        val terminal = coordinator?.snapshot() ?: snapshot
        if (terminal?.callId != null) {
            if (terminal.phase != CallPhase.Ended) runCatching { coordinator?.fail() }
            coordinator?.snapshot()?.let { ended ->
                CallServiceState.publish(ended)
                if (unexpected || CallUiStateStore.snapshot().phase != CallPhase.Ended) {
                    CallUiStateStore.sync(ended, CallEndReason.Failed)
                }
            }
            runCatching { coordinator?.finish() }
        }
        CallServiceState.reset()
        CallUiStateStore.snapshot().takeIf { it.phase == CallPhase.Ended }?.callId?.let { callId ->
            handler.postDelayed({ CallUiStateStore.reset(callId) }, EndedStateLifetimeMillis)
        }
        media = null
        mediaDispatcher = null
        socket = null
        httpClient = null
        coordinator = null
        super.onDestroy()
    }

    private fun ensureRuntime(): Boolean {
        if (coordinator != null) return true
        val auth = AuthStore(SharedPreferencesKeyValueStore(this), AndroidKeystoreTokenCipher())
        val session = auth.load() ?: return false
        val newHttpClient = signalingHttpClient()
        val newSocket = SignalSocket(
            newHttpClient,
            session,
            deviceId = DeviceIdentity.id(this),
        )
        val newCoordinator = CallCoordinator(
            session.login,
            newSocket,
            serverFeatures = session.features,
        )
        val newMediaDispatcher = CallMediaDispatcher()
        lateinit var newMedia: ForegroundCallController
        fun routeMediaCallback(
            onDropped: () -> Unit = {},
            onFailure: (Throwable) -> Unit = { failure ->
                Log.e(CallLogTag, "media operation failed", failure)
            },
            action: (ForegroundCallController) -> Unit,
        ) {
            val accepted = newMediaDispatcher.dispatch {
                if (finishing || media !== newMedia) {
                    onDropped()
                    return@dispatch
                }
                runCatching { action(newMedia) }.onFailure(onFailure)
            }
            if (!accepted) onDropped()
        }
        fun postMediaCallback(
            onDropped: () -> Unit = {},
            action: () -> Unit,
        ) {
            val accepted = newMediaDispatcher.dispatch {
                if (!finishing && media === newMedia) {
                    runCatching(action).onFailure { failure ->
                        Log.e(CallLogTag, "media callback failed", failure)
                    }
                } else {
                    onDropped()
                }
            }
            if (!accepted) onDropped()
        }
        fun failCurrentMedia(failure: Throwable) {
            Log.e(CallLogTag, "media signaling failed", failure)
            handler.post {
                if (finishing || media !== newMedia) return@post
                newCoordinator.fail()
                publish(CallEndReason.Failed)
                finishCallSoon()
            }
        }
        newMedia = ForegroundCallController(
            signal = newSocket,
            mediaFactory = { callId, videoAllowed, iceServers, onLocalIce, onLocalIceRemoved, onIceRestartNeeded ->
                val mediaStatsSession = statsRequestGate.openSession(callId).also { statsSession = it }
                WebRtcCallSession.create(
                    this,
                    videoAllowed = videoAllowed,
                    iceServers = iceServers,
                    forceRelay = BuildConfig.FORCE_RELAY,
                    onLocalIceCandidate = { candidate ->
                        postMediaCallback { onLocalIce(candidate) }
                    },
                    onLocalIceCandidatesRemoved = { candidates ->
                        postMediaCallback { onLocalIceRemoved(candidates) }
                    },
                    onIceRestartNeeded = {
                        postMediaCallback(action = onIceRestartNeeded)
                    },
                    onConnectionStateChanged = { state ->
                        routeMediaCallback { controller ->
                            val connection = statsRequestGate.onConnection(mediaStatsSession, state)
                                ?: return@routeMediaCallback
                            controller.onMediaConnection(callId, connection.epoch, state)
                            handler.post {
                                if (finishing || media !== newMedia) return@post
                                if (state == MediaConnectionState.Connected) {
                                    newCoordinator.mediaConnected()
                                }
                                if (CallUiStateStore.snapshot().callId == callId) {
                                    if (!connection.transportReady || connection.becameReady) {
                                        connectionHealthClassifier.reset()
                                    }
                                    CallUiStateStore.onMediaConnection(state)
                                }
                            }
                        }
                    },
                    onRemoteVideoTrack = { track ->
                        routeMediaCallback(onDropped = track::close) {
                            it.onRemoteVideoTrack(callId, track)
                        }
                    },
                    cameraCallbacks = CameraMediaCallbacks(
                        onLocalTrackChanged = { track ->
                            routeMediaCallback(onDropped = { track?.close() }) {
                                it.onLocalVideoTrack(callId, track)
                            }
                        },
                        onCaptureStarted = { facing ->
                            routeMediaCallback { it.onCameraCaptureStarted(callId, facing) }
                        },
                        onCaptureInvalidated = {
                            routeMediaCallback { it.onCameraCaptureInvalidated(callId) }
                        },
                        onCaptureStopped = {
                            routeMediaCallback { it.onCameraCaptureStopped(callId) }
                        },
                        onFacingChanged = { facing ->
                            routeMediaCallback { it.onCameraFacingChanged(callId, facing) }
                        },
                        onFailure = { message ->
                            Log.e(CallLogTag, "camera failed for call $callId: $message")
                            routeMediaCallback { it.onCameraFailure(callId, message) }
                        },
                    ),
                )
            },
            onVideoStateChanged = { state ->
                val publish = {
                    if (!finishing && media === newMedia) {
                        state.failure?.let { message ->
                            Log.e(CallLogTag, "camera state failed for call ${state.callId}: $message")
                        }
                        VideoCallStateStore.publish(state)
                    }
                }
                if (Looper.myLooper() == Looper.getMainLooper()) publish() else handler.post(publish)
            },
            prepareCameraStart = { callId, lease ->
                val snapshot = CallServiceState.snapshot()
                val permissionGranted = ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.CAMERA,
                ) == PackageManager.PERMISSION_GRANTED
                synchronized(foregroundLock) {
                    val prepared = media === newMedia &&
                        snapshot.callId == callId &&
                        snapshot.phase == CallPhase.Active &&
                        permissionGranted &&
                        updateForegroundType(cameraSending = true)
                    if (prepared) cameraForegroundLease = CameraForegroundLease(newMedia, lease)
                    prepared
                }
            },
            onCameraLeaseReleased = { lease ->
                synchronized(foregroundLock) {
                    val activeLease = cameraForegroundLease
                    if (media === newMedia && activeLease?.owner === newMedia && activeLease.id == lease) {
                        cameraForegroundLease = null
                        updateForegroundType(cameraSending = false)
                    }
                }
            },
        )
        socket = newSocket
        httpClient = newHttpClient
        coordinator = newCoordinator
        media = newMedia
        mediaDispatcher = newMediaDispatcher
        val runtimeOwnerGeneration = runtimeGeneration
        networkObserver = DefaultNetworkObserver(applicationContext) {
            handler.post {
                if (finishing || runtimeGeneration != runtimeOwnerGeneration || socket !== newSocket) return@post
                connected = false
                migrateCallNetwork(
                    newSocket::reconnectNow,
                    { routeMediaCallback { it.onNetworkChanged() } },
                )
            }
        }
        newSocket.connect(
            onEvent = { incoming ->
                handler.post {
                    if (socket !== newSocket || finishing) return@post
                    try {
                        if (newCoordinator.onEvent(incoming)) {
                            val snapshot = newCoordinator.snapshot()
                            routeMediaCallback(onFailure = ::failCurrentMedia) {
                                it.onSignalEvent(snapshot, incoming.event)
                            }
                        }
                    } catch (_: Exception) {
                        newCoordinator.fail()
                        publish()
                        finishCallSoon()
                        return@post
                    }
                    if (incoming.event.type == "call.accept" && newCoordinator.snapshot().phase == CallPhase.Active) {
                        if (incoming.event.payload["crossed"]?.asBoolean == true) {
                            outgoingPeer?.let { peer ->
                                CallUiStateStore.begin(incoming.event.callId, peer, CallDirection.Outgoing, CallPhase.Active)
                                CallUiStateStore.setAudioEndpoints(incoming.event.callId, CallAudioState.snapshot())
                            }
                            IncomingCallController().finishTerminalPresentation(
                                this@CallForegroundService,
                                incoming.event.callId,
                            ) {
                                IncomingCallNotifier(this@CallForegroundService).cancel()
                            }
                            telecomCallId?.takeIf { it != incoming.event.callId }?.let {
                                telecom.cancel(incoming.event.callId)
                            }
                        }
                        routeMediaCallback { it.setActive(true) }
                        val localCallId = telecomCallId ?: incoming.event.callId
                        telecom.setActive(localCallId) { success ->
                            if (!success) {
                                val snapshot = CallServiceState.snapshot()
                                if (snapshot.callId == incoming.event.callId &&
                                    snapshot.phase == CallPhase.Active &&
                                    telecomCallId == localCallId
                                ) {
                                    end(this)
                                }
                            }
                        }
                    }
                    publish(incoming.event.endReason())
                    if (newCoordinator.snapshot().phase == CallPhase.Ended) finishCallUnlessAwaitingTerminalSignal()
                }
            },
            onOpen = { connectionGeneration ->
                handler.post {
                    if (socket !== newSocket || finishing || !newSocket.isOpen(connectionGeneration)) return@post
                    connected = true
                    routeMediaCallback(onFailure = ::failCurrentMedia) { it.onSignalConnected() }
                    newCoordinator.resume()
                    if (newCoordinator.snapshot().phase == CallPhase.Ended) finishCallUnlessAwaitingTerminalSignal()
                }
            },
            onDisconnected = {
                handler.post {
                    if (socket === newSocket && !newSocket.isOpen()) connected = false
                }
            },
            onError = { failure ->
                if (failure.code == SessionReplacedReason) auth.invalidateIfCurrent(session)
                handler.post {
                    if (failure.code == SessionReplacedReason) {
                        if (socket !== newSocket || finishing) return@post
                        connected = false
                        newCoordinator.fail()
                        publish(CallEndReason.Failed)
                        finishCall()
                        return@post
                    }
                    if (socket !== newSocket || finishing) return@post
                    val current = newCoordinator.snapshot()
                    val activeCallId = current.callId.takeIf { current.phase != CallPhase.Ended }
                    routeMediaCallback { it.onSignalFailure(failure) }
                    val reason = signalingFailureEndReason(failure, activeCallId) ?: return@post
                    newCoordinator.fail()
                    publish(reason)
                    if (reason == CallEndReason.Busy) {
                        finishCallAfter(BusyToneDelayMillis)
                    } else {
                        finishCallSoon()
                    }
                }
            },
        )
        return true
    }

    private fun handle(intent: Intent) {
        val call = coordinator ?: return
        val invite = IncomingCallController.inviteFrom(intent)
        var endReason: CallEndReason? = null
        var awaitingTerminalSignal = false
        fun terminalSettlement(): () -> Unit {
            awaitingTerminalSignal = true
            val expectedGeneration = runtimeGeneration
            return terminalSignalGate.begin {
                handler.post { finishCallSoon(expectedGeneration) }
            }
        }
        when (intent.action) {
            ActionStart -> {
                if (call.snapshot().phase != CallPhase.Idle) return
                connectionHealthClassifier.reset()
                CallServiceState.publish(call.snapshot())
                CallUiStateStore.reset()
                CallAudioState.reset()
                VideoCallStateStore.reset()
                val callee = intent.getStringExtra(ExtraCallee) ?: return
                val displayName = intent.getStringExtra(ExtraDisplayName).orEmpty().ifEmpty { callee }
                call.startCall(callee)
                call.snapshot().callId?.let { callId ->
                    val peer = CallPeer(displayName = displayName, login = callee)
                    telecomCallId = callId
                    outgoingPeer = peer
                    CallUiStateStore.begin(
                        callId,
                        peer,
                        CallDirection.Outgoing,
                        CallPhase.Connecting,
                    )
                    telecom.addOutgoing(callId, displayName, telecomCallbacks(callId) { end(this) })
                }
            }
            ActionAnswer -> {
                invite ?: return
                if (call.snapshot().phase != CallPhase.Idle && call.snapshot().callId != invite.callId) return
                if (call.snapshot().phase == CallPhase.Idle &&
                    IncomingCallController().isTerminal(this, invite.callId)
                ) {
                    stopTerminalTelecomStart(call, intent)
                    return
                }
                telecomCallId = invite.callId
                connectionHealthClassifier.reset()
                CallUiStateStore.begin(
                    invite.callId,
                    CallPeer(displayName = invite.caller.ifEmpty { "TiniTalk" }, login = invite.callerLogin),
                    CallDirection.Incoming,
                    CallPhase.Ringing,
                )
                CallUiStateStore.setAudioEndpoints(invite.callId, CallAudioState.snapshot())
                call.restoreIncoming(invite.callId, invite.lastSeq, acknowledgeRinging = false)
                call.resume()
                if (call.snapshot().phase == CallPhase.Ringing) call.accept()
                if (call.snapshot().phase == CallPhase.Active) dispatchMedia { it.setActive(true) }
                IncomingCallController().finishTerminalPresentation(this, invite.callId) {
                    IncomingCallNotifier(this).cancel()
                }
            }
            ActionReject -> {
                invite ?: return
                if (call.snapshot().phase != CallPhase.Idle && call.snapshot().callId != invite.callId) return
                telecomCallId = invite.callId
                CallUiStateStore.begin(
                    invite.callId,
                    CallPeer(displayName = invite.caller.ifEmpty { "TiniTalk" }, login = invite.callerLogin),
                    CallDirection.Incoming,
                    CallPhase.Ringing,
                )
                call.restoreIncoming(invite.callId, invite.lastSeq, acknowledgeRinging = false)
                call.resume()
                if (call.snapshot().phase == CallPhase.Ringing) call.reject(terminalSettlement())
                endReason = CallEndReason.Rejected
                IncomingCallController().clear(this, invite.callId)
            }
            ActionDisconnect -> {
                if (invite != null) {
                    if (telecomCallId != null && telecomCallId != invite.callId) return
                    telecomCallId = invite.callId
                    call.restoreIncoming(invite.callId, invite.lastSeq, acknowledgeRinging = false)
                }
                if (invite != null && call.snapshot().callId != invite.callId) return
                if (call.snapshot().phase == CallPhase.Active) {
                    call.hangUp(terminalSettlement())
                    endReason = CallEndReason.LocalHangup
                } else if (call.snapshot().phase == CallPhase.Ringing) {
                    call.reject(terminalSettlement())
                    endReason = CallEndReason.Rejected
                }
            }
            ActionEnd -> {
                val requestedCallId = intent.getStringExtra(ExtraCallId)
                val current = call.snapshot()
                if (requestedCallId != null && current.callId != null && requestedCallId != current.callId) return
                when (current.phase) {
                    CallPhase.Active -> {
                        call.hangUp(terminalSettlement())
                        endReason = CallEndReason.LocalHangup
                    }
                    CallPhase.Connecting -> {
                        call.cancel(terminalSettlement())
                        endReason = CallEndReason.Cancelled
                    }
                    CallPhase.Ringing -> if (CallUiStateStore.snapshot().direction == CallDirection.Outgoing) {
                        call.cancel(terminalSettlement())
                        endReason = CallEndReason.Cancelled
                    } else {
                        call.reject(terminalSettlement())
                        endReason = CallEndReason.Rejected
                    }
                    CallPhase.Idle, CallPhase.Ended -> {
                        finishing = true
                        releaseCallResources(requestedCallId)
                        stopSelf()
                        return
                    }
                }
            }
            ActionRemoteEnd -> {
                val callId = intent.getStringExtra(ExtraCallId) ?: return
                val current = call.snapshot()
                if (current.callId != callId) {
                    if (current.phase == CallPhase.Idle || current.phase == CallPhase.Ended) {
                        finishing = true
                        releaseCallResources(callId)
                        stopSelf()
                    }
                    return
                }
                if (current.phase != CallPhase.Ended) {
                    call.fail()
                    endReason = CallEndReason.RemoteHangup
                }
            }
            ActionMute -> {
                val muted = intent.getBooleanExtra(ExtraMuted, false)
                dispatchMedia { it.setMuted(muted) }
                CallUiStateStore.setMuted(muted)
            }
            ActionTelecomActive -> {
                if (!acceptsTelecomCallback(call, intent)) {
                    stopTerminalTelecomStart(call, intent)
                    return
                }
                dispatchMedia { it.setActive(true) }
            }
            ActionTelecomInactive -> {
                if (!acceptsTelecomCallback(call, intent)) {
                    stopTerminalTelecomStart(call, intent)
                    return
                }
                dispatchMedia { it.setActive(false) }
            }
            ActionSelectEndpoint -> {
                val callId = intent.getStringExtra(ExtraCallId) ?: return
                val localCallId = TelecomActionScope.telecomCallForSelection(call.snapshot(), callId, telecomCallId) ?: return
                intent.getStringExtra(ExtraEndpointId)?.let { telecom.selectEndpoint(localCallId, it) }
            }
            ActionCameraRequest,
            ActionCameraForeground,
            ActionCameraSwitch -> {
                val cameraAction = cameraCallAction(intent) ?: return
                val activeCallId = call.snapshot().takeIf { it.phase == CallPhase.Active }?.callId ?: return
                if (cameraAction.callId != activeCallId) return
                when (cameraAction) {
                    is CameraCallAction.Request -> dispatchMedia {
                        it.setCameraRequested(
                            cameraAction.callId,
                            cameraAction.requested,
                            permissionGranted = cameraAction.requested,
                        )
                    }
                    is CameraCallAction.Foreground -> dispatchMedia {
                        it.setCameraForeground(
                            cameraAction.callId,
                            cameraAction.foreground,
                            cameraAction.permissionGranted,
                        )
                    }
                    is CameraCallAction.Switch -> dispatchMedia { it.switchCamera(cameraAction.callId) }
                }
            }
        }
        publish(endReason)
        if (call.snapshot().phase == CallPhase.Ended) {
            if (awaitingTerminalSignal) releaseCallResources() else finishCallUnlessAwaitingTerminalSignal()
        }
    }

    private fun publish(endReason: CallEndReason? = null) {
        coordinator?.snapshot()?.let { snapshot ->
            CallServiceState.publish(snapshot)
            CallUiStateStore.sync(snapshot, endReason)
            handler.post(::updateStatsPolling)
        }
    }

    private fun dispatchMedia(action: (ForegroundCallController) -> Unit) {
        val currentMedia = media ?: return
        val currentDispatcher = mediaDispatcher ?: return
        currentDispatcher.dispatch {
            if (finishing || media !== currentMedia) return@dispatch
            runCatching { action(currentMedia) }.onFailure { failure ->
                Log.e(CallLogTag, "media operation failed", failure)
            }
        }
    }

    private fun updateStatsPolling() {
        if (finishing || CallServiceState.snapshot().phase != CallPhase.Active) {
            stopStatsPolling()
        } else {
            val networkLock = callNetworkLock ?: CallNetworkLock.create(this).also { callNetworkLock = it }
            networkLock.setActive(true)
            if (!statsPolling) {
                statsPolling = true
                handler.postDelayed(statsTask, CallDiagnostics.IntervalMillis)
            }
        }
    }

    private fun stopStatsPolling() {
        statsPolling = false
        handler.removeCallbacks(statsTask)
        statsRequestGate.reset()
        statsSession = null
        callNetworkLock?.setActive(false)
        connectionHealthClassifier.reset()
    }

    private fun finishCallSoon(expectedGeneration: Long = runtimeGeneration) {
        finishCallAfter(FinishToneDelayMillis, expectedGeneration)
    }

    private fun finishCallUnlessAwaitingTerminalSignal() {
        if (!terminalSignalGate.isWaiting()) finishCallSoon()
    }

    private fun finishCallAfter(delayMillis: Long, expectedGeneration: Long = runtimeGeneration) {
        handler.postDelayed({ finishCall(expectedGeneration) }, delayMillis)
    }

    private fun finishCall(expectedGeneration: Long = runtimeGeneration) {
        if (finishing || expectedGeneration != runtimeGeneration) return
        finishing = true
        terminalSignalGate.close()
        releaseCallResources()
        stopSelf()
    }

    private fun releaseCallResources(callIdHint: String? = null) {
        if (callResourcesReleased) {
            endSystemCall(callIdHint)
            return
        }
        callResourcesReleased = true
        stopStatsPolling()
        val snapshot = coordinator?.snapshot()
        val callId = snapshot?.callId ?: callIdHint ?: CallUiStateStore.snapshot().callId
        CallAudioState.reset()
        val currentMedia = media
        val currentDispatcher = mediaDispatcher
        media = null
        mediaDispatcher = null
        VideoCallStateStore.reset()
        endSystemCall(callId)
        if (currentMedia != null) {
            val cleanupDispatcher = currentDispatcher ?: CallMediaDispatcher()
            cleanupDispatcher.dispatch {
                runCatching { currentMedia.close() }.onFailure { failure ->
                    Log.e(CallLogTag, "failed to release call media", failure)
                }
            }
            cleanupDispatcher.close()
        } else {
            currentDispatcher?.close()
        }
    }

    private fun endSystemCall(callId: String?) {
        runCatching {
            IncomingCallController().finishTerminalPresentation(this, callId) {
                IncomingCallNotifier(this).cancel()
            }
        }
        listOfNotNull(telecomCallId, callId).distinct().forEach { id ->
            runCatching { telecom.cancel(id) }
        }
        telecomCallId = null
        synchronized(foregroundLock) {
            cameraForegroundLease = null
            cameraForegroundTypeEnabled = false
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            runCatching { getSystemService(NotificationManager::class.java).cancel(NotificationId) }
        }
    }

    private fun satisfyTerminalForegroundStart(): Boolean = synchronized(foregroundLock) {
        try {
            ServiceCompat.startForeground(
                this,
                NotificationId,
                terminalNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL,
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            getSystemService(NotificationManager::class.java).cancel(NotificationId)
            true
        } catch (failure: Throwable) {
            Log.e(CallLogTag, "failed to settle late foreground service start", failure)
            false
        }
    }

    private fun telecomCallbacks(callId: String, onDisconnect: () -> Unit): TelecomCallCallbacks {
        val ownerGeneration = runtimeGeneration
        fun dispatchIfOwned(action: () -> Unit) {
            handler.post {
                val current = coordinator?.snapshot()
                val ownsCall = !finishing &&
                    !callResourcesReleased &&
                    runtimeGeneration == ownerGeneration &&
                    current != null &&
                    TelecomActionScope.acceptsCallback(
                        current,
                        pendingIncomingCallId = null,
                        pendingExpiresAt = null,
                        localTelecomCallId = telecomCallId,
                        callbackCallId = callId,
                        now = Instant.now(),
                    )
                if (ownsCall) action()
            }
        }
        return TelecomCallCallbacks(
            onDisconnect = { dispatchIfOwned(onDisconnect) },
            onActive = { dispatchIfOwned { telecomActive(this, callId) } },
            onInactive = { dispatchIfOwned { telecomInactive(this, callId) } },
            onEndpointsChanged = { state ->
                dispatchIfOwned { CallAudioState.publish(coordinator?.snapshot()?.callId ?: callId, state) }
            },
        )
    }

    private fun acceptsTelecomCallback(call: CallCoordinator, intent: Intent): Boolean {
        val callId = intent.getStringExtra(ExtraCallId) ?: return false
        val pending = IncomingCallController().load(this)?.invite
        return TelecomActionScope.acceptsCallback(
            call.snapshot(),
            pending?.callId,
            pending?.expiresAt,
            telecomCallId,
            callId,
            Instant.now(),
        )
    }

    private fun stopTerminalTelecomStart(call: CallCoordinator, intent: Intent) {
        if (call.snapshot().phase != CallPhase.Idle && call.snapshot().phase != CallPhase.Ended) return
        finishing = true
        releaseCallResources(intent.getStringExtra(ExtraCallId))
        stopSelf()
    }

    private fun notification(state: CallUiState): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, ChannelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val content = PendingIntent.getActivity(
            this,
            0,
            CallActivity.ongoingIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val hangUp = PendingIntent.getService(
            this,
            1,
            Intent(this, CallForegroundService::class.java)
                .setAction(ActionEnd)
                .putExtra(ExtraCallId, state.callId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val peerName = state.peer?.displayName?.takeIf(String::isNotBlank) ?: "TiniTalk"
        val status = when (state.phase) {
            CallPhase.Ringing -> if (state.direction == CallDirection.Outgoing) "Ждём ответа…" else "Входящий звонок"
            CallPhase.Connecting -> "Пробуем связаться…"
            CallPhase.Active -> if (state.muted) "Микрофон выключен" else "Звонок идёт"
            CallPhase.Ended -> if (state.endReason == CallEndReason.Busy) "Занято" else "Звонок завершён"
            CallPhase.Idle -> "Звонок"
        }
        builder
            .setSmallIcon(callNotificationIcon(state))
            .setContentTitle(peerName)
            .setContentText(status)
            .setCategory(Notification.CATEGORY_CALL)
            .setContentIntent(content)
            .setOngoing(true)
        state.connectedAtElapsedMs?.takeIf { state.phase == CallPhase.Active }?.let { connectedAt ->
            val elapsed = (SystemClock.elapsedRealtime() - connectedAt).coerceAtLeast(0L)
            builder
                .setWhen(System.currentTimeMillis() - elapsed)
                .setUsesChronometer(true)
                .setShowWhen(true)
        } ?: builder.setShowWhen(false)
        if (Build.VERSION.SDK_INT >= 31) {
            builder.setStyle(
                Notification.CallStyle.forOngoingCall(
                    Person.Builder().setName(peerName).setImportant(true).build(),
                    hangUp,
                ),
            )
        } else {
            @Suppress("DEPRECATION")
            builder.addAction(Notification.Action.Builder(R.drawable.ic_call, "Завершить", hangUp).build())
        }
        return builder.build()
    }

    private fun terminalNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, ChannelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.drawable.ic_call_active)
            .setContentTitle("TiniTalk")
            .setContentText("Завершаем звонок…")
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(ChannelId, "Активные звонки", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun updateForegroundType(cameraSending: Boolean): Boolean = synchronized(foregroundLock) {
        if (callResourcesReleased) return@synchronized false
        val state = CallUiStateStore.snapshot()
        try {
            ServiceCompat.startForeground(
                this,
                NotificationId,
                notification(state),
                callForegroundServiceType(cameraSending),
            )
            cameraForegroundTypeEnabled = cameraSending
            true
        } catch (failure: Throwable) {
            Log.e(CallLogTag, "failed to update foreground service type", failure)
            false
        }
    }

    companion object {
        const val ActionStart = "org.tinitalk.action.START_CALL"
        const val ActionAnswer = "org.tinitalk.action.SERVICE_ANSWER_CALL"
        const val ActionReject = "org.tinitalk.action.SERVICE_REJECT_CALL"
        const val ActionDisconnect = "org.tinitalk.action.SERVICE_DISCONNECT_CALL"
        const val ActionEnd = "org.tinitalk.action.END_CALL"
        const val ActionRemoteEnd = "org.tinitalk.action.REMOTE_END_CALL"
        const val ActionMute = "org.tinitalk.action.MUTE_CALL"
        const val ActionTelecomActive = "org.tinitalk.action.TELECOM_ACTIVE"
        const val ActionTelecomInactive = "org.tinitalk.action.TELECOM_INACTIVE"
        const val ActionSelectEndpoint = "org.tinitalk.action.SELECT_AUDIO_ENDPOINT"
        const val ActionCameraRequest = "org.tinitalk.action.CAMERA_REQUEST"
        const val ActionCameraForeground = "org.tinitalk.action.CAMERA_FOREGROUND"
        const val ActionCameraSwitch = "org.tinitalk.action.CAMERA_SWITCH"
        private const val ExtraCallee = "callee"
        private const val ExtraDisplayName = "display_name"
        private const val ExtraMuted = "muted"
        internal const val ExtraCallId = "call_id"
        private const val ExtraEndpointId = "endpoint_id"
        internal const val ExtraCameraRequested = "camera_requested"
        internal const val ExtraCameraForeground = "camera_foreground"
        internal const val ExtraCameraPermission = "camera_permission"
        const val ChannelId = "calls"
        const val NotificationId = 10
        private const val TerminalSignalTimeoutMillis = 20_000L
        private const val FinishToneDelayMillis = 450L
        private const val BusyToneDelayMillis = 2_200L
        private const val EndedStateLifetimeMillis = 1_000L
        private const val CallLogTag = "TiniTalkCall"
        fun startOutgoing(context: Context, callee: String, displayName: String = callee): Boolean {
            if (!context.networkAvailability().canStartNetworkAction()) return false
            start(
                context,
                Intent(context, CallForegroundService::class.java)
                    .setAction(ActionStart)
                    .putExtra(ExtraCallee, callee)
                    .putExtra(ExtraDisplayName, displayName),
            )
            return true
        }

        fun end(context: Context) {
            val intent = Intent(context, CallForegroundService::class.java).setAction(ActionEnd)
            CallServiceState.snapshot().callId?.let { intent.putExtra(ExtraCallId, it) }
            start(context, intent)
        }

        fun remoteEnded(context: Context, callId: String) {
            start(
                context,
                Intent(context, CallForegroundService::class.java)
                    .setAction(ActionRemoteEnd)
                    .putExtra(ExtraCallId, callId),
            )
        }

        fun mute(context: Context, muted: Boolean) {
            start(context, Intent(context, CallForegroundService::class.java).setAction(ActionMute).putExtra(ExtraMuted, muted))
        }

        fun telecomActive(context: Context, callId: String) {
            start(context, Intent(context, CallForegroundService::class.java).setAction(ActionTelecomActive).putExtra(ExtraCallId, callId))
        }

        fun telecomInactive(context: Context, callId: String) {
            start(context, Intent(context, CallForegroundService::class.java).setAction(ActionTelecomInactive).putExtra(ExtraCallId, callId))
        }

        fun selectAudioEndpoint(context: Context, callId: String, endpointId: String) {
            start(
                context,
                Intent(context, CallForegroundService::class.java)
                    .setAction(ActionSelectEndpoint)
                    .putExtra(ExtraCallId, callId)
                    .putExtra(ExtraEndpointId, endpointId),
            )
        }

        fun cameraRequested(context: Context, callId: String, requested: Boolean) {
            start(context, cameraRequestIntent(context, callId, requested))
        }

        fun cameraForeground(
            context: Context,
            callId: String,
            foreground: Boolean,
            permissionGranted: Boolean,
        ) {
            start(context, cameraForegroundIntent(context, callId, foreground, permissionGranted))
        }

        fun switchCamera(context: Context, callId: String) {
            start(context, cameraSwitchIntent(context, callId))
        }

        internal fun cameraRequestIntent(context: Context, callId: String, requested: Boolean): Intent =
            Intent(context, CallForegroundService::class.java)
                .setAction(ActionCameraRequest)
                .putExtra(ExtraCallId, callId)
                .putExtra(ExtraCameraRequested, requested)

        internal fun cameraForegroundIntent(
            context: Context,
            callId: String,
            foreground: Boolean,
            permissionGranted: Boolean,
        ): Intent = Intent(context, CallForegroundService::class.java)
            .setAction(ActionCameraForeground)
            .putExtra(ExtraCallId, callId)
            .putExtra(ExtraCameraForeground, foreground)
            .putExtra(ExtraCameraPermission, permissionGranted)

        internal fun cameraSwitchIntent(context: Context, callId: String): Intent =
            Intent(context, CallForegroundService::class.java)
                .setAction(ActionCameraSwitch)
                .putExtra(ExtraCallId, callId)

        fun start(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
        }
    }
}

internal fun rejectOfflineOutgoingStart(action: String?, networkAvailable: Boolean): Boolean =
    action == CallForegroundService.ActionStart && !networkAvailable

internal fun signalingFailureEndReason(failure: SignalFailure, currentCallId: String?): CallEndReason? = when {
    currentCallId == null -> null
    failure.callId != null && failure.callId != currentCallId -> null
    failure.code == "busy" -> CallEndReason.Busy
    failure.code == "ice_rate_limited" ||
        failure.code == "ice_restart_rate_limited" ||
        failure.code == "ice_restart_request_rate_limited" -> null
    else -> CallEndReason.Failed
}

private fun org.tinitalk.data.signal.SignalEvent.endReason(): CallEndReason? = when (type) {
    "call.reject" -> CallEndReason.Rejected
    "call.cancel" -> CallEndReason.Cancelled
    "call.end" -> CallEndReason.RemoteHangup
    "call.expire" -> CallEndReason.TimedOut
    else -> null
}
