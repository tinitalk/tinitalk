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
import android.util.Log
import org.tinitalk.BuildConfig
import org.tinitalk.MainActivity
import org.tinitalk.R
import org.tinitalk.call.CallCoordinator
import org.tinitalk.call.CallAudioState
import org.tinitalk.call.CallPhase
import org.tinitalk.call.CallServiceState
import org.tinitalk.call.ForegroundCallController
import org.tinitalk.data.AndroidKeystoreTokenCipher
import org.tinitalk.data.AuthStore
import org.tinitalk.data.SharedPreferencesKeyValueStore
import org.tinitalk.data.signal.SignalSocket
import org.tinitalk.media.WebRtcAudioSession
import org.tinitalk.push.IncomingCallNotifier
import okhttp3.OkHttpClient
import java.time.Instant
import java.util.concurrent.TimeUnit

internal fun signalingHttpClient(): OkHttpClient =
    OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

class CallForegroundService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var socket: SignalSocket? = null
    private var httpClient: OkHttpClient? = null
    private var coordinator: CallCoordinator? = null
    private var media: ForegroundCallController? = null
    private var connected = false
    @Volatile private var finishing = false
    @Volatile private var statsPolling = false
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
                            Log.i(
                                CallLogTag,
                                "rtt_ms=${stats.rttMs} jitter_ms=${stats.jitterMs} loss_percent=${stats.packetLossPercent} " +
                                    "bitrate_kbps=${stats.bitrateKbps} local_candidate_type=${stats.localCandidateType} " +
                                    "remote_candidate_type=${stats.remoteCandidateType}",
                            )
                        }
                    }
                }
            }
            if (statsPolling) handler.postDelayed(this, StatsIntervalMillis)
        }
    }
    private val telecom by lazy { TelecomCallController(AndroidTelecomRegistrar(this)) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        startForeground(NotificationId, notification())
        if (intent == null || !ensureRuntime()) {
            stopSelf()
            return START_NOT_STICKY
        }
        handle(intent)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopStatsPolling()
        val snapshot = coordinator?.snapshot()
        val unexpected = !finishing
        if (unexpected && connected && snapshot?.phase == CallPhase.Active) {
            runCatching { coordinator?.hangUp() }
        }
        runCatching { socket?.close() }
        runCatching { media?.close() }
        runCatching { httpClient?.dispatcher?.executorService?.shutdownNow() }
        runCatching { httpClient?.connectionPool?.evictAll() }
        CallAudioState.reset()
        if (unexpected) {
            val terminal = coordinator?.snapshot() ?: snapshot
            if (terminal?.callId != null) {
                runCatching { CallServiceState.publish(terminal.copy(phase = CallPhase.Ended)) }
                runCatching { CallServiceState.reset() }
            }
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
        val newSocket = SignalSocket(newHttpClient, session)
        val newCoordinator = CallCoordinator(session.login, newSocket)
        val newMedia = ForegroundCallController(
            signal = newSocket,
            mediaFactory = { _, iceServers, onLocalIce, onIceRestartNeeded ->
                WebRtcAudioSession.create(
                    this,
                    iceServers = iceServers,
                    forceRelay = BuildConfig.FORCE_RELAY,
                    onLocalIceCandidate = onLocalIce,
                    onIceRestartNeeded = onIceRestartNeeded,
                )
            },
        )
        socket = newSocket
        httpClient = newHttpClient
        coordinator = newCoordinator
        media = newMedia
        newSocket.connect(
            onEvent = { incoming ->
                if (newCoordinator.onEvent(incoming)) {
                    newMedia.onSignalEvent(newCoordinator.snapshot(), incoming.event)
                }
                if (incoming.event.type == "call.accept" && newCoordinator.snapshot().phase == CallPhase.Active) {
                    telecom.setActive(incoming.event.callId)
                }
                publish()
                if (newCoordinator.snapshot().phase == CallPhase.Ended) finishCall()
            },
            onOpen = {
                connected = true
                newCoordinator.resume()
                if (newCoordinator.snapshot().phase == CallPhase.Ended) finishCallSoon()
            },
            onDisconnected = { connected = false },
            onError = {
                newCoordinator.fail()
                publish()
                finishCall()
            },
        )
        return true
    }

    private fun handle(intent: Intent) {
        val call = coordinator ?: return
        val invite = IncomingCallController.inviteFrom(intent)
        when (intent.action) {
            ActionStart -> {
                CallServiceState.publish(call.snapshot())
                CallAudioState.reset()
                val callee = intent.getStringExtra(ExtraCallee) ?: return
                call.startCall(callee)
                call.snapshot().callId?.let { callId ->
                    telecom.addOutgoing(callId, callee, telecomCallbacks(callId) { end(this) })
                }
            }
            ActionAnswer -> {
                invite ?: return
                if (call.snapshot().phase != CallPhase.Idle && call.snapshot().callId != invite.callId) return
                call.restoreIncoming(invite.callId, invite.lastSeq)
                call.resume()
                if (call.snapshot().phase == CallPhase.Ringing) call.accept()
                IncomingCallNotifier(this).cancel()
                IncomingCallController().clear(this, invite.callId)
            }
            ActionReject -> {
                invite ?: return
                if (call.snapshot().phase != CallPhase.Idle && call.snapshot().callId != invite.callId) return
                call.restoreIncoming(invite.callId, invite.lastSeq)
                call.resume()
                if (call.snapshot().phase == CallPhase.Ringing) call.reject()
                IncomingCallController().clear(this, invite.callId)
            }
            ActionDisconnect -> {
                invite?.let { call.restoreIncoming(it.callId, it.lastSeq) }
                if (invite != null && call.snapshot().callId != invite.callId) return
                if (call.snapshot().phase == CallPhase.Active) call.hangUp() else if (call.snapshot().phase == CallPhase.Ringing) call.reject()
            }
            ActionEnd -> when (call.snapshot().phase) {
                CallPhase.Active -> call.hangUp()
                CallPhase.Connecting -> call.cancel()
                CallPhase.Ringing -> call.reject()
                else -> Unit
            }
            ActionMute -> media?.setMuted(intent.getBooleanExtra(ExtraMuted, false))
            ActionTelecomActive -> {
                if (!acceptsTelecomCallback(call, intent)) return
                media?.setActive(true)
            }
            ActionTelecomInactive -> {
                if (!acceptsTelecomCallback(call, intent)) return
                media?.setActive(false)
            }
            ActionEndpointsChanged -> {
                if (!acceptsTelecomCallback(call, intent)) return
                AudioEndpointStateCodec.decode(intent.getStringExtra(ExtraAudioEndpoints))?.let(CallAudioState::publish)
            }
            ActionSelectEndpoint -> {
                val callId = intent.getStringExtra(ExtraCallId) ?: return
                if (!TelecomActionScope.acceptsSelection(call.snapshot(), callId)) return
                intent.getStringExtra(ExtraEndpointId)?.let { telecom.selectEndpoint(callId, it) }
            }
        }
        publish()
        if (call.snapshot().phase == CallPhase.Ended) {
            if (connected) finishCallSoon() else handler.postDelayed({ finishCall() }, SignalFlushTimeoutMillis)
        }
    }

    private fun publish() {
        coordinator?.snapshot()?.let { snapshot ->
            CallServiceState.publish(snapshot)
            handler.post(::updateStatsPolling)
        }
    }

    private fun updateStatsPolling() {
        if (finishing || CallServiceState.snapshot().phase != CallPhase.Active) {
            stopStatsPolling()
        } else if (!statsPolling) {
            statsPolling = true
            handler.postDelayed(statsTask, StatsIntervalMillis)
        }
    }

    private fun stopStatsPolling() {
        statsPolling = false
        handler.removeCallbacks(statsTask)
    }

    private fun finishCallSoon() {
        handler.postDelayed({ finishCall() }, 300)
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
        callId?.let(telecom::cancel)
        if (snapshot?.phase == CallPhase.Ended) {
            coordinator?.finish()
            publish()
        }
        stopSelf()
    }

    private fun telecomCallbacks(callId: String, onDisconnect: () -> Unit) = TelecomCallCallbacks(
        onDisconnect = onDisconnect,
        onActive = { telecomActive(this, callId) },
        onInactive = { telecomInactive(this, callId) },
        onEndpointsChanged = { state -> endpointsChanged(this, callId, state) },
    )

    private fun acceptsTelecomCallback(call: CallCoordinator, intent: Intent): Boolean {
        val callId = intent.getStringExtra(ExtraCallId) ?: return false
        val pending = IncomingCallController().load(this)?.invite
        return TelecomActionScope.acceptsCallback(
            call.snapshot(),
            pending?.callId,
            pending?.expiresAt,
            callId,
            Instant.now(),
        )
    }

    private fun notification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, ChannelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val content = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val hangUp = PendingIntent.getService(
            this,
            1,
            Intent(this, CallForegroundService::class.java).setAction(ActionEnd),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        builder
            .setSmallIcon(R.drawable.ic_call)
            .setContentTitle("TiniTalk call")
            .setContentText("Call in progress")
            .setContentIntent(content)
            .setOngoing(true)
        if (Build.VERSION.SDK_INT >= 31) {
            builder.setStyle(
                Notification.CallStyle.forOngoingCall(
                    Person.Builder().setName("TiniTalk").setImportant(true).build(),
                    hangUp,
                ),
            )
        } else {
            @Suppress("DEPRECATION")
            builder.addAction(Notification.Action.Builder(R.drawable.ic_call, "Hang up", hangUp).build())
        }
        return builder.build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(ChannelId, "Calls", NotificationManager.IMPORTANCE_LOW),
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
        const val ActionEndpointsChanged = "org.tinitalk.action.TELECOM_ENDPOINTS_CHANGED"
        const val ActionSelectEndpoint = "org.tinitalk.action.SELECT_AUDIO_ENDPOINT"
        private const val ExtraCallee = "callee"
        private const val ExtraMuted = "muted"
        private const val ExtraCallId = "call_id"
        private const val ExtraEndpointId = "endpoint_id"
        private const val ExtraAudioEndpoints = "audio_endpoints"
        const val ChannelId = "calls"
        const val NotificationId = 10
        private const val SignalFlushTimeoutMillis = 5_000L
        private const val StatsIntervalMillis = 10_000L
        private const val CallLogTag = "TiniTalkCall"
        fun startOutgoing(context: Context, callee: String) {
            start(context, Intent(context, CallForegroundService::class.java).setAction(ActionStart).putExtra(ExtraCallee, callee))
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

        fun endpointsChanged(context: Context, callId: String, state: AudioEndpointState) {
            start(
                context,
                Intent(context, CallForegroundService::class.java)
                    .setAction(ActionEndpointsChanged)
                    .putExtra(ExtraCallId, callId)
                    .putExtra(ExtraAudioEndpoints, AudioEndpointStateCodec.encode(state)),
            )
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
