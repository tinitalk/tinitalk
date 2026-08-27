package org.tinitalk.telecom

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
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
import org.tinitalk.call.ForegroundCallController
import org.tinitalk.data.AndroidKeystoreTokenCipher
import org.tinitalk.data.AuthStore
import org.tinitalk.data.SharedPreferencesKeyValueStore
import org.tinitalk.data.signal.SignalSocket
import org.tinitalk.data.signal.SignalFailure
import org.tinitalk.media.WebRtcAudioSession
import org.tinitalk.media.ConnectionHealthClassifier
import org.tinitalk.media.DefaultNetworkObserver
import org.tinitalk.media.MediaConnectionState
import org.tinitalk.push.DeviceRegistrar
import org.tinitalk.push.IncomingCallNotifier
import okhttp3.OkHttpClient
import java.time.Instant
import java.util.concurrent.TimeUnit

internal fun signalingHttpClient(): OkHttpClient =
    OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

internal fun migrateCallNetwork(
    reconnectSignaling: () -> Unit,
    restartMedia: () -> Unit,
) {
    reconnectSignaling()
    restartMedia()
}

class CallForegroundService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var socket: SignalSocket? = null
    private var httpClient: OkHttpClient? = null
    private var coordinator: CallCoordinator? = null
    private var media: ForegroundCallController? = null
    @Volatile private var connected = false
    private var finishing = false
    private var statsPolling = false
    private var telecomCallId: String? = null
    private var outgoingPeer: CallPeer? = null
    private var callNetworkLock: CallNetworkLock? = null
    private var networkObserver: DefaultNetworkObserver? = null
    private lateinit var callTones: CallToneController
    private val connectionHealthClassifier = ConnectionHealthClassifier()
    private val callUiObserver: (CallUiState) -> Unit = { state ->
        handler.post {
            if (!finishing) {
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
            if (activeCallId != null && currentMedia != null) {
                currentMedia.getStats { stats ->
                    handler.post {
                        val snapshot = CallServiceState.snapshot()
                        val stillActive = statsPolling && !finishing &&
                            snapshot.phase == CallPhase.Active && snapshot.callId == activeCallId
                        if (stillActive && media === currentMedia) {
                            Log.i(CallLogTag, CallDiagnostics.format(stats))
                            val currentHealth = CallUiStateStore.snapshot().connectionHealth
                            val health = connectionHealthClassifier.update(stats, currentHealth)
                            CallUiStateStore.setConnectionHealth(activeCallId, health)
                        }
                    }
                }
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
        if (finishing) return START_NOT_STICKY
        startForeground(NotificationId, notification(CallUiStateStore.snapshot()))
        if (intent == null || !ensureRuntime()) {
            stopSelf()
            return START_NOT_STICKY
        }
        handle(intent)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        val unexpected = !finishing
        finishing = true
        CallUiStateStore.removeObserver(callUiObserver)
        callTones.close()
        stopStatsPolling()
        callNetworkLock?.close()
        callNetworkLock = null
        networkObserver?.close()
        networkObserver = null
        val snapshot = coordinator?.snapshot()
        if (unexpected && connected && snapshot?.phase == CallPhase.Active) {
            runCatching { coordinator?.hangUp() }
        }
        runCatching { socket?.close() }
        runCatching { media?.close() }
        runCatching { httpClient?.dispatcher?.executorService?.shutdownNow() }
        runCatching { httpClient?.connectionPool?.evictAll() }
        CallAudioState.reset()
        val terminal = coordinator?.snapshot() ?: snapshot
        if (terminal?.callId != null) {
            if (terminal.phase != CallPhase.Ended) runCatching { coordinator?.fail() }
            coordinator?.snapshot()?.let(CallServiceState::publish)
            runCatching { coordinator?.finish() }
        }
        CallServiceState.reset()
        CallUiStateStore.snapshot().takeIf { it.phase == CallPhase.Ended }?.callId?.let { callId ->
            handler.postDelayed({ CallUiStateStore.reset(callId) }, EndedStateLifetimeMillis)
        }
        media = null
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
            deviceId = DeviceRegistrar.deviceId(this),
        )
        val newCoordinator = CallCoordinator(session.login, newSocket)
        val newMedia = ForegroundCallController(
            signal = newSocket,
            mediaFactory = { _, iceServers, onLocalIce, onLocalIceRemoved, onIceRestartNeeded ->
                WebRtcAudioSession.create(
                    this,
                    iceServers = iceServers,
                    forceRelay = BuildConfig.FORCE_RELAY,
                    onLocalIceCandidate = onLocalIce,
                    onLocalIceCandidatesRemoved = onLocalIceRemoved,
                    onIceRestartNeeded = onIceRestartNeeded,
                    onConnectionStateChanged = { state ->
                        handler.post {
                            if (!finishing && state == MediaConnectionState.Connected) {
                                newCoordinator.mediaConnected()
                            }
                            val callId = newCoordinator.snapshot().callId
                            if (!finishing && callId != null && CallUiStateStore.snapshot().callId == callId) {
                                connectionHealthClassifier.reset()
                                CallUiStateStore.onMediaConnection(state)
                            }
                        }
                    },
                )
            },
        )
        socket = newSocket
        httpClient = newHttpClient
        coordinator = newCoordinator
        media = newMedia
        networkObserver = DefaultNetworkObserver(applicationContext) {
            connected = false
            migrateCallNetwork(newSocket::reconnectNow, newMedia::onNetworkChanged)
        }
        newSocket.connect(
            onEvent = { incoming ->
                handler.post {
                    if (socket !== newSocket || finishing) return@post
                    try {
                        if (newCoordinator.onEvent(incoming)) {
                            newMedia.onSignalEvent(newCoordinator.snapshot(), incoming.event)
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
                            IncomingCallController().apply {
                                rememberTerminal(this@CallForegroundService, incoming.event.callId)
                                clear(this@CallForegroundService, incoming.event.callId)
                            }
                            IncomingCallNotifier(this@CallForegroundService).cancel()
                            telecomCallId?.takeIf { it != incoming.event.callId }?.let {
                                telecom.cancel(incoming.event.callId)
                            }
                        }
                        newMedia.setActive(true)
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
                    if (newCoordinator.snapshot().phase == CallPhase.Ended) finishCallSoon()
                }
            },
            onOpen = { connectionGeneration ->
                handler.post {
                    if (socket !== newSocket || finishing || !newSocket.isOpen(connectionGeneration)) return@post
                    connected = true
                    newMedia.onSignalConnected()
                    newCoordinator.resume()
                    if (newCoordinator.snapshot().phase == CallPhase.Ended) finishCallSoon()
                }
            },
            onDisconnected = {
                handler.post {
                    if (socket === newSocket && !newSocket.isOpen()) connected = false
                }
            },
            onError = { failure ->
                handler.post {
                    if (socket !== newSocket || finishing) return@post
                    val current = newCoordinator.snapshot()
                    val activeCallId = current.callId.takeIf { current.phase != CallPhase.Ended }
                    newMedia.onSignalFailure(failure)
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
        when (intent.action) {
            ActionStart -> {
                if (call.snapshot().phase != CallPhase.Idle) return
                connectionHealthClassifier.reset()
                CallServiceState.publish(call.snapshot())
                CallUiStateStore.reset()
                CallAudioState.reset()
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
                telecomCallId = invite.callId
                connectionHealthClassifier.reset()
                CallUiStateStore.begin(
                    invite.callId,
                    CallPeer(displayName = invite.caller.ifEmpty { "TiniTalk" }, login = invite.callerLogin),
                    CallDirection.Incoming,
                    CallPhase.Ringing,
                )
                CallUiStateStore.setAudioEndpoints(invite.callId, CallAudioState.snapshot())
                call.restoreIncoming(invite.callId, invite.lastSeq)
                call.resume()
                if (call.snapshot().phase == CallPhase.Ringing) call.accept()
                if (call.snapshot().phase == CallPhase.Active) media?.setActive(true)
                IncomingCallNotifier(this).cancel()
                IncomingCallController().clear(this, invite.callId)
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
                call.restoreIncoming(invite.callId, invite.lastSeq)
                call.resume()
                if (call.snapshot().phase == CallPhase.Ringing) call.reject()
                endReason = CallEndReason.Rejected
                IncomingCallController().clear(this, invite.callId)
            }
            ActionDisconnect -> {
                if (invite != null) {
                    if (telecomCallId != null && telecomCallId != invite.callId) return
                    telecomCallId = invite.callId
                    call.restoreIncoming(invite.callId, invite.lastSeq)
                }
                if (invite != null && call.snapshot().callId != invite.callId) return
                if (call.snapshot().phase == CallPhase.Active) {
                    call.hangUp()
                    endReason = CallEndReason.LocalHangup
                } else if (call.snapshot().phase == CallPhase.Ringing) {
                    call.reject()
                    endReason = CallEndReason.Rejected
                }
            }
            ActionEnd -> when (call.snapshot().phase) {
                CallPhase.Active -> {
                    call.hangUp()
                    endReason = CallEndReason.LocalHangup
                }
                CallPhase.Connecting -> {
                    call.cancel()
                    endReason = CallEndReason.Cancelled
                }
                CallPhase.Ringing -> if (CallUiStateStore.snapshot().direction == CallDirection.Outgoing) {
                    call.cancel()
                    endReason = CallEndReason.Cancelled
                } else {
                    call.reject()
                    endReason = CallEndReason.Rejected
                }
                else -> Unit
            }
            ActionMute -> {
                val muted = intent.getBooleanExtra(ExtraMuted, false)
                media?.setMuted(muted)
                CallUiStateStore.setMuted(muted)
            }
            ActionTelecomActive -> {
                if (!acceptsTelecomCallback(call, intent)) {
                    if (call.snapshot().phase == CallPhase.Idle) stopSelf()
                    return
                }
                media?.setActive(true)
            }
            ActionTelecomInactive -> {
                if (!acceptsTelecomCallback(call, intent)) {
                    if (call.snapshot().phase == CallPhase.Idle) stopSelf()
                    return
                }
                media?.setActive(false)
            }
            ActionSelectEndpoint -> {
                val callId = intent.getStringExtra(ExtraCallId) ?: return
                val localCallId = TelecomActionScope.telecomCallForSelection(call.snapshot(), callId, telecomCallId) ?: return
                intent.getStringExtra(ExtraEndpointId)?.let { telecom.selectEndpoint(localCallId, it) }
            }
        }
        publish(endReason)
        if (call.snapshot().phase == CallPhase.Ended) {
            if (connected) finishCallSoon() else handler.postDelayed({ finishCall() }, SignalFlushTimeoutMillis)
        }
    }

    private fun publish(endReason: CallEndReason? = null) {
        coordinator?.snapshot()?.let { snapshot ->
            CallServiceState.publish(snapshot)
            CallUiStateStore.sync(snapshot, endReason)
            handler.post(::updateStatsPolling)
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
        callNetworkLock?.setActive(false)
        connectionHealthClassifier.reset()
    }

    private fun finishCallSoon() {
        finishCallAfter(FinishToneDelayMillis)
    }

    private fun finishCallAfter(delayMillis: Long) {
        handler.postDelayed({ finishCall() }, delayMillis)
    }

    private fun finishCall() {
        if (finishing) return
        finishing = true
        stopStatsPolling()
        val snapshot = coordinator?.snapshot()
        val callId = snapshot?.callId
        CallAudioState.reset()
        media?.close()
        IncomingCallNotifier(this).cancel()
        callId?.let { IncomingCallController().clear(this, it) }
        (telecomCallId ?: callId)?.let(telecom::cancel)
        stopSelf()
    }

    private fun telecomCallbacks(callId: String, onDisconnect: () -> Unit) = TelecomCallCallbacks(
        onDisconnect = onDisconnect,
        onActive = { telecomActive(this, callId) },
        onInactive = { telecomInactive(this, callId) },
        onEndpointsChanged = { state -> CallAudioState.publish(coordinator?.snapshot()?.callId ?: callId, state) },
    )

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
            Intent(this, CallForegroundService::class.java).setAction(ActionEnd),
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
            .setSmallIcon(R.drawable.ic_call)
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

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(ChannelId, "Активные звонки", NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object {
        const val ActionStart = "org.tinitalk.action.START_CALL"
        const val ActionAnswer = "org.tinitalk.action.SERVICE_ANSWER_CALL"
        const val ActionReject = "org.tinitalk.action.SERVICE_REJECT_CALL"
        const val ActionDisconnect = "org.tinitalk.action.SERVICE_DISCONNECT_CALL"
        const val ActionEnd = "org.tinitalk.action.END_CALL"
        const val ActionMute = "org.tinitalk.action.MUTE_CALL"
        const val ActionTelecomActive = "org.tinitalk.action.TELECOM_ACTIVE"
        const val ActionTelecomInactive = "org.tinitalk.action.TELECOM_INACTIVE"
        const val ActionSelectEndpoint = "org.tinitalk.action.SELECT_AUDIO_ENDPOINT"
        private const val ExtraCallee = "callee"
        private const val ExtraDisplayName = "display_name"
        private const val ExtraMuted = "muted"
        private const val ExtraCallId = "call_id"
        private const val ExtraEndpointId = "endpoint_id"
        const val ChannelId = "calls"
        const val NotificationId = 10
        private const val SignalFlushTimeoutMillis = 5_000L
        private const val FinishToneDelayMillis = 450L
        private const val BusyToneDelayMillis = 2_200L
        private const val EndedStateLifetimeMillis = 1_000L
        private const val CallLogTag = "TiniTalkCall"
        fun startOutgoing(context: Context, callee: String, displayName: String = callee) {
            start(
                context,
                Intent(context, CallForegroundService::class.java)
                    .setAction(ActionStart)
                    .putExtra(ExtraCallee, callee)
                    .putExtra(ExtraDisplayName, displayName),
            )
        }

        fun end(context: Context) {
            start(context, Intent(context, CallForegroundService::class.java).setAction(ActionEnd))
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

        fun start(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
        }
    }
}

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
