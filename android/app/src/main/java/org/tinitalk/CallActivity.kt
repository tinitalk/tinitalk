package org.tinitalk

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.core.view.WindowCompat
import androidx.core.telecom.CallEndpointCompat
import org.tinitalk.call.CallDirection
import org.tinitalk.call.CallEndReason
import org.tinitalk.call.CallPeer
import org.tinitalk.call.CallPhase
import org.tinitalk.call.CallScreenAction
import org.tinitalk.call.CallScreenActionGate
import org.tinitalk.call.CallServiceState
import org.tinitalk.call.CallUiState
import org.tinitalk.call.CallUiStateStore
import org.tinitalk.call.ConnectionHealth
import org.tinitalk.call.outgoingVisibleState
import org.tinitalk.call.shouldDismissIncomingOverlay
import org.tinitalk.push.IncomingCallNotifier
import org.tinitalk.push.IncomingInvite
import org.tinitalk.push.IncomingRingingAcknowledger
import org.tinitalk.telecom.CallForegroundService
import org.tinitalk.telecom.IncomingAnswerClaim
import org.tinitalk.telecom.IncomingCallController
import org.tinitalk.telecom.ProximityController
import org.tinitalk.ui.call.ActiveCallScreen
import org.tinitalk.ui.call.EndedCallScreen
import org.tinitalk.ui.call.IncomingCallScreen
import org.tinitalk.ui.call.OutgoingCallScreen
import org.tinitalk.ui.theme.CallBackgroundBottom
import org.tinitalk.ui.theme.CallBackgroundTop
import org.tinitalk.ui.theme.TiniTalkTheme
import java.time.Instant
import kotlinx.coroutines.delay

class CallActivity : ComponentActivity() {
    private val incomingController = IncomingCallController()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var proximityController: ProximityController
    private var activityStarted = false
    private val actionGate = CallScreenActionGate()
    private lateinit var ringingAcknowledger: IncomingRingingAcknowledger
    private var callState by mutableStateOf(CallUiStateStore.snapshot())
    private var incomingInvite by mutableStateOf<IncomingInvite?>(null)
    private var outgoingLogin by mutableStateOf<String?>(null)
    private var outgoingName by mutableStateOf<String?>(null)

    private val callObserver: (CallUiState) -> Unit = { state ->
        runOnUiThread {
            actionGate.onCallState(state)
            callState = state
            if (incomingInvite?.callId == state.callId && state.phase != CallPhase.Ringing) {
                ringingAcknowledger.stop()
            }
            updateProximity()
        }
    }

    private val inviteMonitor = object : Runnable {
        override fun run() {
            val invite = incomingInvite ?: return
            if (!isCurrentIncoming(invite)) {
                finish()
                return
            }
            if (callState.callId == invite.callId && callState.phase != CallPhase.Ringing) return
            handler.postDelayed(this, InviteCheckIntervalMillis)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        proximityController = ProximityController(this)
        ringingAcknowledger = IncomingRingingAcknowledger(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        applyIntent(intent)
        CallUiStateStore.observe(callObserver)

        setContent {
            TiniTalkTheme(darkTheme = true) {
                SideEffect {
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = false
                        isAppearanceLightNavigationBars = false
                    }
                }

                val visibleState = visibleCallState()
                val peerName = visibleState.peer?.displayName
                    ?.takeIf { it.isNotBlank() }
                    ?: incomingInvite?.caller?.takeIf { it.isNotBlank() }
                    ?: outgoingName?.takeIf { it.isNotBlank() }
                    ?: "TiniTalk"
                val durationText = rememberDurationText(visibleState)

                when {
                    visibleState.phase == CallPhase.Ended -> EndedCallScreen(peerName, visibleState.endReason)
                    visibleState.phase == CallPhase.Active -> ActiveCallScreen(
                        peerName = peerName,
                        durationText = durationText,
                        muted = visibleState.muted,
                        connectionHealth = visibleState.connectionHealth,
                        currentEndpoint = visibleState.currentAudioEndpoint,
                        availableEndpoints = visibleState.availableAudioEndpoints,
                        onMute = { CallForegroundService.mute(this, it) },
                        onSelectEndpoint = { endpoint ->
                            visibleState.callId?.let { callId ->
                                CallForegroundService.selectAudioEndpoint(this, callId, endpoint.id)
                            }
                        },
                        onEnd = { endCall(visibleState) },
                    )
                    visibleState.direction == CallDirection.Incoming && visibleState.phase == CallPhase.Ringing -> {
                        val invite = incomingInvite
                        if (invite == null) {
                            EmptyCallSurface()
                        } else {
                            IncomingCallScreen(
                                callId = invite.callId,
                                caller = peerName,
                                onAnswer = { answer(invite) },
                                onReject = { reject(invite) },
                            )
                        }
                    }
                    visibleState.phase == CallPhase.Ringing || visibleState.phase == CallPhase.Connecting -> {
                        OutgoingCallScreen(
                            callee = peerName,
                            status = if (visibleState.phase == CallPhase.Ringing) "Ждём ответа…" else "Пробуем связаться…",
                            muted = visibleState.muted,
                            currentEndpoint = visibleState.currentAudioEndpoint,
                            availableEndpoints = visibleState.availableAudioEndpoints,
                            onMute = { CallForegroundService.mute(this, it) },
                            onSelectEndpoint = { endpoint ->
                                visibleState.callId?.let { callId ->
                                    CallForegroundService.selectAudioEndpoint(this, callId, endpoint.id)
                                }
                            },
                            onCancel = { endCall(visibleState) },
                        )
                    }
                    else -> EmptyCallSurface()
                }

                LaunchedEffect(visibleState.callId, visibleState.phase) {
                    when (visibleState.phase) {
                        CallPhase.Ended -> {
                            delay(if (visibleState.endReason == CallEndReason.Busy) BusyScreenMillis else EndedScreenMillis)
                            finish()
                        }
                        CallPhase.Idle -> {
                            delay(IdleGraceMillis)
                            if (visibleCallState().phase == CallPhase.Idle) finish()
                        }
                        else -> Unit
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        activityStarted = true
        incomingInvite?.takeUnless(::isCurrentIncoming)?.let {
            finish()
            return
        }
        showIncomingCallFullScreen()
        updateProximity()
        handler.removeCallbacks(inviteMonitor)
        if (incomingInvite != null) handler.post(inviteMonitor)
    }

    override fun onStop() {
        activityStarted = false
        restoreIncomingCallNotification()
        updateProximity()
        handler.removeCallbacks(inviteMonitor)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (applyIntent(intent)) setIntent(intent)
        showIncomingCallFullScreen()
    }

    override fun onDestroy() {
        handler.removeCallbacks(inviteMonitor)
        ringingAcknowledger.close()
        proximityController.close()
        CallUiStateStore.removeObserver(callObserver)
        super.onDestroy()
    }

    private fun applyIntent(intent: Intent?): Boolean {
        val invite = IncomingCallController.inviteFrom(intent)
        if (invite != null) {
            val answerRequested = intent?.action == IncomingCallController.ActionAnswer
            val answerClaim = if (answerRequested) {
                val liveCall = callState.phase != CallPhase.Idle && callState.phase != CallPhase.Ended
                when {
                    liveCall && callState.callId == invite.callId -> IncomingAnswerClaim.AlreadyClaimed
                    liveCall -> IncomingAnswerClaim.Invalid
                    else -> incomingController.claimAnswer(this, invite)
                }
            } else {
                null
            }
            if (answerClaim == IncomingAnswerClaim.Invalid) {
                if (callState.phase == CallPhase.Idle || callState.phase == CallPhase.Ended) finish()
                return false
            }
            if (answerRequested) intent.action = IncomingCallController.ActionIncoming
            if (incomingInvite?.callId != invite.callId) actionGate.reset()
            incomingInvite = invite
            outgoingLogin = null
            outgoingName = null
            if (answerClaim == IncomingAnswerClaim.Claimed) answer(invite)
            return true
        }
        val login = intent?.getStringExtra(ExtraOutgoingLogin) ?: return false
        val redial = intent.action == ActionRedial
        if (redial) intent.action = null
        val servicePhase = CallServiceState.snapshot().phase
        if (redial && servicePhase != CallPhase.Idle && servicePhase != CallPhase.Ended) {
            incomingInvite = null
            outgoingLogin = null
            outgoingName = null
            return true
        }
        if (outgoingLogin != login) actionGate.reset()
        outgoingLogin = login
        outgoingName = intent.getStringExtra(ExtraOutgoingName).orEmpty().ifBlank { login }
        incomingInvite = null
        if (redial) {
            CallForegroundService.startOutgoing(this, login, outgoingName.orEmpty())
        }
        return true
    }

    private fun visibleCallState(): CallUiState {
        val invite = incomingInvite
        if (invite != null && callState.callId != invite.callId) {
            return CallUiState(
                callId = invite.callId,
                peer = CallPeer(invite.caller.ifBlank { "TiniTalk" }, invite.callerLogin),
                direction = CallDirection.Incoming,
                phase = CallPhase.Ringing,
            )
        }
        val login = outgoingLogin
        if (login != null) return outgoingVisibleState(callState, login, outgoingName.orEmpty())
        return callState
    }

    private fun answer(invite: IncomingInvite) {
        if (!actionGate.lock(CallScreenAction.Answer, invite.callId)) return
        incomingController.answer(this, invite)
    }

    private fun reject(invite: IncomingInvite) {
        if (!actionGate.lock(CallScreenAction.Reject, invite.callId)) return
        incomingController.reject(this, invite)
    }

    private fun endCall(state: CallUiState) {
        val actionKey = incomingInvite?.callId
            ?: outgoingLogin?.let { "outgoing:$it" }
            ?: state.callId
            ?: return
        if (!actionGate.lock(CallScreenAction.End, actionKey)) return
        CallForegroundService.end(this)
    }

    private fun updateProximity() {
        if (!::proximityController.isInitialized) return
        val connected = callState.connectionHealth == ConnectionHealth.Good ||
            callState.connectionHealth == ConnectionHealth.Poor
        val earpiece = callState.currentAudioEndpoint?.type == CallEndpointCompat.TYPE_EARPIECE
        val outgoingDial = callState.direction == CallDirection.Outgoing &&
            (callState.phase == CallPhase.Connecting || callState.phase == CallPhase.Ringing)
        val activeConversation = callState.phase == CallPhase.Active &&
            callState.connectedAtElapsedMs != null && connected
        proximityController.setEnabled(
            activityStarted && earpiece && (outgoingDial || activeConversation),
        )
    }

    private fun showIncomingCallFullScreen() {
        val invite = incomingInvite ?: return
        if (!isCurrentIncoming(invite)) return
        if (shouldDismissIncomingOverlay(activityStarted, visibleCallState())) {
            IncomingCallNotifier(this).fullScreenShown(invite)
            ringingAcknowledger.acknowledge(invite)
        }
    }

    private fun restoreIncomingCallNotification() {
        val invite = incomingInvite ?: return
        val stillRinging = !actionGate.isLocked(invite.callId) &&
            isCurrentIncoming(invite) &&
            visibleCallState().phase == CallPhase.Ringing
        if (stillRinging) IncomingCallNotifier(this).fullScreenHidden(invite)
    }

    private fun isCurrentIncoming(invite: IncomingInvite): Boolean {
        if (!invite.expiresAt.isAfter(Instant.now()) || incomingController.isTerminal(this, invite.callId)) {
            return false
        }
        val pending = incomingController.load(this)
        if (pending?.invite?.callId == invite.callId && pending.action == IncomingCallController.ActionReject) {
            return false
        }
        val storedCallMatches = pending?.invite?.callId == invite.callId
        val liveCallMatches = callState.callId == invite.callId &&
            callState.phase != CallPhase.Idle && callState.phase != CallPhase.Ended
        return storedCallMatches || liveCallMatches
    }

    companion object {
        private const val ExtraOutgoingLogin = "outgoing_login"
        private const val ExtraOutgoingName = "outgoing_name"
        private const val ActionRedial = "org.tinitalk.action.REDIAL"
        private const val InviteCheckIntervalMillis = 500L
        private const val IdleGraceMillis = 1_000L
        private const val EndedScreenMillis = 900L
        private const val BusyScreenMillis = 2_200L

        fun outgoingIntent(context: Context, login: String, displayName: String): Intent =
            Intent(context, CallActivity::class.java)
                .putExtra(ExtraOutgoingLogin, login)
                .putExtra(ExtraOutgoingName, displayName)

        fun ongoingIntent(context: Context): Intent =
            Intent(context, CallActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        fun redialIntent(context: Context, login: String, displayName: String): Intent =
            outgoingIntent(context, login, displayName)
                .setAction(ActionRedial)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}

@androidx.compose.runtime.Composable
private fun EmptyCallSurface() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(CallBackgroundTop, CallBackgroundBottom))),
    )
}

@androidx.compose.runtime.Composable
private fun rememberDurationText(state: CallUiState): String {
    var nowElapsedMs by remember(state.callId, state.connectedAtElapsedMs) {
        mutableLongStateOf(SystemClock.elapsedRealtime())
    }
    LaunchedEffect(state.callId, state.phase, state.connectedAtElapsedMs) {
        val connectedAt = state.connectedAtElapsedMs ?: return@LaunchedEffect
        while (state.phase == CallPhase.Active) {
            nowElapsedMs = SystemClock.elapsedRealtime()
            val elapsed = (nowElapsedMs - connectedAt).coerceAtLeast(0L)
            delay(1_000L - elapsed % 1_000L)
        }
    }
    return state.durationText(nowElapsedMs) ?: "00:00"
}
