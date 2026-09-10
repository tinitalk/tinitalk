package org.tinitalk

import androidx.core.net.toUri
import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.core.content.ContextCompat
import androidx.core.os.BundleCompat
import androidx.core.telecom.CallEndpointCompat
import org.tinitalk.call.CallDirection
import org.tinitalk.call.CallReplySupport
import org.tinitalk.call.CallReplyCode
import org.tinitalk.call.CallReplyResult
import org.tinitalk.call.CallReplyResultStore
import org.tinitalk.call.CallScreenVisibility
import org.tinitalk.call.AccountCallKey
import org.tinitalk.call.AccountCallOwner
import org.tinitalk.call.CallSessionBinding
import org.tinitalk.call.CallEndReason
import org.tinitalk.call.CallPeer
import org.tinitalk.call.CallPhase
import org.tinitalk.call.CallScreenAction
import org.tinitalk.call.CallScreenActionGate
import org.tinitalk.call.CallServiceState
import org.tinitalk.call.CallUiState
import org.tinitalk.call.CallUiStateStore
import org.tinitalk.call.CallVideoState
import org.tinitalk.call.VideoCallStateStore
import org.tinitalk.call.ConnectionHealth
import org.tinitalk.call.resolvePinnedCallSession
import org.tinitalk.call.restoreEndedCallState
import org.tinitalk.call.restoreEndedCallBinding
import org.tinitalk.call.saveEndedState
import org.tinitalk.call.GlobalCallAdmission
import org.tinitalk.call.shouldDismissIncomingOverlay
import org.tinitalk.data.AndroidKeystoreTokenCipher
import org.tinitalk.data.AccountId
import org.tinitalk.data.AccountPeerKey
import org.tinitalk.data.AccountUnreadState
import org.tinitalk.data.AuthStore
import org.tinitalk.data.AuthSessionEvent
import org.tinitalk.data.AuthSessionEvents
import org.tinitalk.data.CallHistoryEvents
import org.tinitalk.data.ContactAddress
import org.tinitalk.data.ContactRepository
import org.tinitalk.data.normalizeServerUrl
import org.tinitalk.data.SharedPreferencesKeyValueStore
import org.tinitalk.data.Session
import org.tinitalk.data.UrlConnectionApiClient
import org.tinitalk.ui.LocalContactPhotoReader
import org.tinitalk.push.IncomingCallNotifier
import org.tinitalk.push.IncomingInvite
import org.tinitalk.push.acknowledgeLatestMissedCall
import org.tinitalk.media.VideoRenderSource
import org.tinitalk.network.NetworkAvailability
import org.tinitalk.network.networkAvailability
import org.tinitalk.telecom.CallForegroundService
import org.tinitalk.telecom.IncomingAnswerClaim
import org.tinitalk.telecom.IncomingCallController
import org.tinitalk.telecom.OutgoingCallStartResult
import org.tinitalk.telecom.ProximityController
import org.tinitalk.ui.call.ActiveCallScreen
import org.tinitalk.ui.call.EndedCallScreen
import org.tinitalk.ui.call.IncomingCallScreen
import org.tinitalk.ui.call.OutgoingCallScreen
import org.tinitalk.ui.theme.CallBackgroundBottom
import org.tinitalk.ui.theme.CallBackgroundTop
import org.tinitalk.ui.theme.TiniTalkTheme
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CallActivity : ComponentActivity() {
    private val incomingController = IncomingCallController()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var proximityController: ProximityController
    private lateinit var network: NetworkAvailability
    private var activityStarted = false
    private var activityResumed = false
    private val visibilityToken = Any()
    private val cameraForegroundPublicationGate = CameraForegroundPublicationGate()
    private lateinit var cameraPermissionRouter: CameraPermissionActionRouter
    private lateinit var cameraForegroundLifecycle: CallActivityCameraForeground
    private val actionGate = CallScreenActionGate()
    private val replySupport = CallReplySupport()
    private var replySupported by mutableStateOf(false)
    private var replyResult by mutableStateOf<CallReplyResult?>(null)
    private var callSessionBinding: CallSessionBinding? = null
    private val replySessionObserver: (AuthSessionEvent) -> Unit = { event ->
        runOnUiThread {
            val result = replyResult
            if (result != null && (event.accountId == null || event.accountId == result.key.accountId) &&
                result.sessionBinding == CallSessionBinding.from(event.session)) dismissReplyResult()
            val state = visibleCallState()
            if (state.phase == CallPhase.Ended && (event.accountId == null || event.accountId == state.accountId) &&
                callSessionBinding?.matches(event.session) == true) {
                callState = CallUiState()
                finish()
            }
        }
    }
    private var callState by mutableStateOf(CallUiStateStore.snapshot())
    private var videoState by mutableStateOf(VideoCallStateStore.snapshot())
    private var renderedVideoCallKey: AccountCallKey? = null
    private var renderedVideoVisible = false
    private var incomingInvite by mutableStateOf<IncomingInvite?>(null)
    private var outgoingCallKey: AccountCallKey? = null
    private var outgoingLogin by mutableStateOf<String?>(null)
    private var outgoingName by mutableStateOf<String?>(null)
    private var outgoingContactAddress by mutableStateOf<ContactAddress?>(null)
    private var pendingOutgoingStart = false
    private var pendingScreenCallKey: AccountCallKey? = null
    private val screenPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val key = pendingScreenCallKey
        pendingScreenCallKey = null
        val current = visibleCallState()
        val permission = result.data
        if (result.resultCode == RESULT_OK && permission != null && key != null &&
            current.callKey == key && current.phase == CallPhase.Active) {
            CallForegroundService.startScreen(this, key, permission)
        }
    }
    private val networkObserver: (Boolean) -> Unit = { available ->
        handler.post {
            val servicePhase = CallServiceState.snapshot().phase
            if (!available && !network.canStartNetworkAction() &&
                pendingOutgoingStart && outgoingLogin != null &&
                (servicePhase == CallPhase.Idle || servicePhase == CallPhase.Ended)
            ) {
                Toast.makeText(this, "Нет подключения к интернету", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (::cameraPermissionRouter.isInitialized) {
            cameraPermissionRouter.onPermissionResult(
                granted,
                visibleCallState(),
                VideoCallStateStore.snapshot(),
            )
        }
    }
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> cameraForegroundLifecycle.onScreenOff()
                Intent.ACTION_SCREEN_ON -> cameraForegroundLifecycle.onScreenOn()
                Intent.ACTION_USER_PRESENT -> cameraForegroundLifecycle.onUserPresent()
            }
        }
    }

    private val callObserver: (CallUiState) -> Unit = { state ->
        runOnUiThread {
            if (state.phase != CallPhase.Idle) {
                pendingOutgoingStart = false
            }
            actionGate.onCallState(state)
            if (state.phase != CallPhase.Idle && state.callKey != callState.callKey) callSessionBinding = null
            // The service releases its state before the ended screen's reading time is over.
            callState = when {
                state.phase != CallPhase.Idle -> state
                callState.phase == CallPhase.Ended -> callState
                callState.phase == CallPhase.Ringing && incomingInvite?.let { invite ->
                    invite.key == callState.callKey && incomingController.isTerminal(this, invite.owner)
                } == true -> callState.onEnded(CallEndReason.Cancelled, SystemClock.elapsedRealtime())
                else -> state
            }
            bindCallSession()
            refreshReplyResult()
            validateEndedCallSession()
            updateEndedScreenTimeout()
            publishVisibleCall()
            if (state.callKey != renderedVideoCallKey || state.phase != CallPhase.Active) {
                renderedVideoCallKey = null
                renderedVideoVisible = false
            }
            updateProximity()
            cameraForegroundLifecycle.onCallStateChanged()
        }
    }

    private val videoObserver: (CallVideoState<VideoRenderSource>) -> Unit = { state ->
        runOnUiThread {
            videoState = state
            if (state.callKey != renderedVideoCallKey) {
                renderedVideoCallKey = null
                renderedVideoVisible = false
            }
            updateProximity()
        }
    }

    private val endedScreenTimeout = Runnable { updateEndedScreenTimeout() }

    private val inviteMonitor = object : Runnable {
        override fun run() {
            val invite = incomingInvite ?: return
            if (callState.callKey == invite.key && callState.phase != CallPhase.Ringing) return
            if (!isCurrentIncoming(invite)) {
                finishIncomingPresentation(invite)
                return
            }
            handler.postDelayed(this, InviteCheckIntervalMillis)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (callState.phase == CallPhase.Idle) {
            val savedEnded = savedInstanceState?.getBundle(StateEndedCall)
            restoreEndedCallState(savedEnded)?.let {
                callState = it
                callSessionBinding = restoreEndedCallBinding(savedEnded)
            }
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        proximityController = ProximityController(this)
        network = networkAvailability()
        pendingScreenCallKey = savedInstanceState?.getString("screen_permission_account")?.let { account ->
            savedInstanceState.getString("screen_permission_call")?.let { AccountCallKey(AccountId(account), it) }
        }
        cameraPermissionRouter = CameraPermissionActionRouter(
            permissionGranted = ::cameraPermissionGranted,
            requestPermission = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
            enableCamera = { callKey -> CallForegroundService.cameraRequested(this, callKey, requested = true) },
            cameraVisible = ::cameraVisible,
            restoredPendingCallKey = savedInstanceState?.getString(StatePendingCameraAccountId)
                ?.let(::AccountId)
                ?.let { accountId ->
                    savedInstanceState.getString(StatePendingCameraCallId)
                        ?.takeIf(String::isNotBlank)
                        ?.let { callId -> AccountCallKey(accountId, callId) }
                },
        )
        cameraForegroundLifecycle = CallActivityCameraForeground(
            screenInteractive = ::screenInteractive,
            retryPendingCamera = {
                cameraPermissionRouter.onVisible(visibleCallState(), VideoCallStateStore.snapshot())
            },
            publish = ::publishCameraForeground,
        )
        ContextCompat.registerReceiver(
            this,
            screenStateReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
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
        val restoredIntent = savedInstanceState?.let {
            BundleCompat.getParcelable(it, StateOutgoingCallIntent, Intent::class.java)
        } ?: intent
        val restoredIncoming = IncomingCallController.inviteFrom(restoredIntent)
        if (callState.phase == CallPhase.Idle && restoredIncoming != null &&
            savedInstanceState?.getString(StatePresentedIncomingOwner) == restoredIncoming.owner.localId()) {
            // Cancellation can arrive between saving the ringing screen and recreating it.
            incomingInvite = restoredIncoming
            if (!isCurrentIncoming(restoredIncoming)) finishIncomingPresentation(restoredIncoming)
        }
        if (!isFinishing && applyIntent(restoredIntent)) setIntent(restoredIntent)
        CallUiStateStore.observe(callObserver)
        VideoCallStateStore.observe(videoObserver)
        network.observe(networkObserver)

        setContent {
            TiniTalkTheme(darkTheme = true) {
                CompositionLocalProvider(LocalContactPhotoReader provides (application as TinitalkApplication).contactPhotoStore) {
                SideEffect {
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = false
                        isAppearanceLightNavigationBars = false
                    }
                }

                val visibleState = visibleCallState()
                BackHandler(replyResult != null) { dismissReplyResult() }
                LaunchedEffect(incomingInvite?.owner, visibleState.phase) {
                    replySupported = false
                    val owner = incomingInvite?.owner
                    if (owner == null || visibleState.phase != CallPhase.Ringing) {
                        replySupport.clear()
                        return@LaunchedEffect
                    }
                    val request = replySupport.begin(owner)
                    val auth = AuthStore(SharedPreferencesKeyValueStore(this@CallActivity), AndroidKeystoreTokenCipher())
                    val info = withContext(Dispatchers.IO) {
                        runCatching {
                            val session = resolvePinnedCallSession(auth, owner.key.accountId, owner.sessionBinding)
                                ?: return@runCatching null
                            UrlConnectionApiClient(session.url, session.login, session.token, session.sessionId).serverInfo()
                        }.getOrNull()
                    }
                    if (incomingInvite?.owner == owner && visibleCallState().phase == CallPhase.Ringing &&
                        incomingInvite?.let(::isCurrentIncoming) == true &&
                        resolvePinnedCallSession(auth, owner.key.accountId, owner.sessionBinding) != null &&
                        replySupport.complete(request, info)
                    ) {
                        replySupported = replySupport.isSupported(owner)
                        if (info?.service == "tinitalk" && info.status == "ok" && info.apiVersion == 4) {
                            auth.updateFeatures(owner.sessionBinding.serverUrl, info.features)
                        }
                    }
                }
                val peerName = visibleState.peer?.displayName
                    ?.takeIf { it.isNotBlank() }
                    ?: incomingInvite?.caller?.takeIf { it.isNotBlank() }
                    ?: outgoingName?.takeIf { it.isNotBlank() }
                    ?: "TiniTalk"
                val durationText = rememberDurationText(visibleState)
                val visibleVideoState = videoState.takeIf { it.callKey == visibleState.callKey }
                    ?: CallVideoState()
                val contactAddress = visibleState.peer?.contactAddress
                val fallbackLogin = visibleState.peer?.login ?: peerName

                when {
                    visibleState.phase == CallPhase.Ended -> EndedCallScreen(
                        peerName,
                        visibleState.endReason,
                        contactAddress,
                        fallbackLogin,
                        reply = replyResult?.code,
                        direction = visibleState.direction,
                        durationText = durationText.takeIf { visibleState.connectedAtElapsedMs != null },
                    )
                    visibleState.phase == CallPhase.Active -> ActiveCallScreen(
                        peerName = peerName,
                        contactAddress = contactAddress,
                        fallbackLogin = fallbackLogin,
                        durationText = durationText,
                        muted = visibleState.muted,
                        connectionHealth = visibleState.connectionHealth,
                        transportRoute = visibleState.transportRoute,
                        currentEndpoint = visibleState.currentAudioEndpoint,
                        availableEndpoints = visibleState.availableAudioEndpoints,
                        videoState = visibleVideoState,
                        onMute = { CallForegroundService.mute(this, it) },
                        onSelectEndpoint = { endpoint ->
                            visibleState.callKey?.let { callKey ->
                                CallForegroundService.selectAudioEndpoint(this, callKey, endpoint.id)
                            }
                        },
                        onCamera = { enabled ->
                            if (enabled) requestCamera() else disableCamera()
                        },
                        onSwitchCamera = ::switchCamera,
                        onVideoVisibilityChanged = { visible ->
                            updateRenderedVideoVisibility(visibleState.callKey, visible)
                        },
                        onEnd = { endCall(visibleState) },
                        onShareScreen = ::requestScreenSharing,
                        onStopSharing = { visibleState.callKey?.let { CallForegroundService.stopScreen(this, it) } },
                        security = visibleState.security,
                    )
                    visibleState.direction == CallDirection.Incoming && visibleState.phase == CallPhase.Ringing -> {
                        val invite = incomingInvite
                        if (invite == null) {
                            EmptyCallSurface()
                        } else {
                            IncomingCallScreen(
                                callId = invite.owner.localId(),
                                caller = peerName,
                                contactAddress = contactAddress,
                                fallbackLogin = fallbackLogin,
                                replySupported = replySupported,
                                onReply = { reject(invite, it) },
                                onReplySheetExpanded = {
                                    if (isCurrentIncoming(invite)) IncomingCallNotifier(this).silence(invite)
                                },
                                onAnswer = { answer(invite) },
                                onReject = { reject(invite) },
                            )
                        }
                    }
                    visibleState.phase == CallPhase.Ringing || visibleState.phase == CallPhase.Connecting -> {
                        OutgoingCallScreen(
                            callee = peerName,
                            contactAddress = contactAddress,
                            fallbackLogin = fallbackLogin,
                            status = if (visibleState.phase == CallPhase.Ringing) "Ждём ответа…" else "Пробуем связаться…",
                            muted = visibleState.muted,
                            currentEndpoint = visibleState.currentAudioEndpoint,
                            availableEndpoints = visibleState.availableAudioEndpoints,
                            onMute = { CallForegroundService.mute(this, it) },
                            onSelectEndpoint = { endpoint ->
                                visibleState.callKey?.let { callKey ->
                                    CallForegroundService.selectAudioEndpoint(this, callKey, endpoint.id)
                                }
                            },
                            onCancel = { endCall(visibleState) },
                        )
                    }
                    else -> EmptyCallSurface()
                }

                LaunchedEffect(visibleState.callKey, visibleState.phase) {
                    if (visibleState.phase == CallPhase.Idle) {
                        delay(IdleGraceMillis)
                        if (visibleCallState().phase == CallPhase.Idle) finish()
                    }
                }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val hadReply = replyResult != null
        refreshReplyResult()
        validateEndedCallSession()
        if (isFinishing) return
        if (hadReply && replyResult == null && (callState.phase == CallPhase.Idle || callState.phase == CallPhase.Ended)) {
            finish()
            return
        }
        AuthSessionEvents.observe(replySessionObserver)
        activityStarted = true
        incomingInvite?.takeUnless(::isCurrentIncoming)?.let(::finishIncomingPresentation)
        if (isFinishing) return
        showIncomingCallFullScreen()
        updateProximity()
        handler.removeCallbacks(inviteMonitor)
        if (incomingInvite != null) handler.post(inviteMonitor)
    }

    override fun onResume() {
        super.onResume()
        // Handler delays do not include deep sleep; recalculate from elapsed time on wake.
        // Wait until onResume: onStart may run before a queued onNewIntent replaces the old call.
        updateEndedScreenTimeout()
        activityResumed = true
        publishVisibleCall()
        cameraForegroundLifecycle.onResume()
    }

    override fun onPause() {
        activityResumed = false
        publishVisibleCall()
        cameraForegroundLifecycle.onPause()
        super.onPause()
    }

    override fun onStop() {
        AuthSessionEvents.removeObserver(replySessionObserver)
        activityStarted = false
        if (!isChangingConfigurations) restoreIncomingCallNotification()
        updateProximity()
        handler.removeCallbacks(inviteMonitor)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (applyIntent(intent)) setIntent(intent)
        updateEndedScreenTimeout()
        publishVisibleCall()
        showIncomingCallFullScreen()
        handler.removeCallbacks(inviteMonitor)
        if (activityStarted && incomingInvite != null) handler.post(inviteMonitor)
    }

    override fun onDestroy() {
        CallScreenVisibility.update(visibilityToken, null)
        handler.removeCallbacks(inviteMonitor)
        handler.removeCallbacks(endedScreenTimeout)
        proximityController.close()
        CallUiStateStore.removeObserver(callObserver)
        VideoCallStateStore.removeObserver(videoObserver)
        network.removeObserver(networkObserver)
        unregisterReceiver(screenStateReceiver)
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        val displayed = visibleCallState()
        displayed.saveEndedState(callSessionBinding)?.let { outState.putBundle(StateEndedCall, it) }
        if (displayed.phase == CallPhase.Ringing) {
            incomingInvite?.takeIf { it.key == displayed.callKey }?.let {
                outState.putString(StatePresentedIncomingOwner, it.owner.localId())
            }
        }
        if (outgoingCallKey != null) outState.putParcelable(StateOutgoingCallIntent, Intent(intent))
        pendingScreenCallKey?.let { key ->
            outState.putString("screen_permission_account", key.accountId.value)
            outState.putString("screen_permission_call", key.callId)
        }
        cameraPermissionRouter.pendingCallKey()?.let { callKey ->
            outState.putString(StatePendingCameraAccountId, callKey.accountId.value)
            outState.putString(StatePendingCameraCallId, callKey.callId)
        }
        super.onSaveInstanceState(outState)
    }

    private fun requestScreenSharing() {
        val current = visibleCallState()
        val key = current.callKey ?: return
        val screen = VideoCallStateStore.snapshot().takeIf { it.callKey == key }?.screen ?: return
        if (current.phase != CallPhase.Active || !screen.allowed || screen.requested || screen.remoteId != null || pendingScreenCallKey != null) return
        pendingScreenCallKey = key
        try {
            screenPermissionLauncher.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
        } catch (_: Exception) {
            pendingScreenCallKey = null
            Toast.makeText(this, "Не удалось открыть разрешение на показ экрана", Toast.LENGTH_LONG).show()
        }
    }

    private fun applyIntent(intent: Intent?): Boolean {
        val invite = IncomingCallController.inviteFrom(intent)
        if (invite != null) {
            val endedCallMatches = callState.callKey == invite.key && callState.phase == CallPhase.Ended
            if (!endedCallMatches && !incomingController.ownsIncoming(this, invite)) {
                if (callState.phase == CallPhase.Idle || callState.phase == CallPhase.Ended) finish()
                return false
            }
            val answerRequested = !endedCallMatches && intent?.action == IncomingCallController.ActionAnswer
            val answerClaim = if (answerRequested) {
                val liveCall = callState.phase != CallPhase.Idle && callState.phase != CallPhase.Ended
                when {
                    liveCall && callState.callKey == invite.key -> IncomingAnswerClaim.AlreadyClaimed
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
            if (incomingInvite?.owner != invite.owner) actionGate.reset()
            CallReplyResultStore(this).clearForNewCall(invite.key)
            replyResult = null
            incomingInvite = invite
            outgoingCallKey = null
            outgoingLogin = null
            outgoingName = null
            outgoingContactAddress = null
            pendingOutgoingStart = false
            if (answerClaim == IncomingAnswerClaim.Claimed) answer(invite)
            return true
        }
        // Ongoing-call banners/notifications may only carry the Activity target.
        // Pin the displayed outgoing call before its shared state is cleared at termination.
        if (intent != null && !intent.hasExtra(ExtraOutgoingLogin) && callState.direction == CallDirection.Outgoing) {
            val key = callState.callKey
            val peer = callState.peer
            if (key != null && peer?.login != null) {
                intent.putExtra(ExtraOutgoingAccountId, key.accountId.value)
                    .putExtra(ExtraOutgoingCallId, key.callId)
                    .putExtra(ExtraOutgoingLogin, peer.login)
                    .putExtra(ExtraOutgoingName, peer.displayName)
                    .putExtra(ExtraOutgoingServerUrl, peer.contactAddress?.serverUrl)
            }
        }
        val login = intent?.getStringExtra(ExtraOutgoingLogin) ?: return false
        val accountId = intent.getStringExtra(ExtraOutgoingAccountId)
            ?.takeIf(String::isNotBlank)
            ?.let(::AccountId)
            ?: return false
        val intentCallId = intent.getStringExtra(ExtraOutgoingCallId)?.takeIf(String::isNotBlank) ?: return false
        val peerKey = AccountPeerKey(accountId, login)
        var key = AccountCallKey(accountId, intentCallId)
        val redial = intent.action == ActionRedial
        if (redial) intent.action = null
        var contactAddress = outgoingContactAddressFrom(intent, login)
        val servicePhase = CallServiceState.snapshot().phase
        var existingCall = false
        var redialStart: OutgoingCallStartResult? = null
        if (redial) {
            val binding = redialBindingFrom(intent) ?: run {
                finish()
                return false
            }
            contactAddress = ContactAddress.of(binding.serverUrl, login)
            val authStore = AuthStore(SharedPreferencesKeyValueStore(this), AndroidKeystoreTokenCipher())
            val accepted = executePinnedRedial(
                authStore,
                peerKey,
                binding,
                acknowledge = { session -> acknowledgeMissedCall(peerKey, session) },
                start = {
                    if (servicePhase != CallPhase.Idle && servicePhase != CallPhase.Ended) {
                        existingCall = true
                    } else {
                        redialStart = CallForegroundService.tryStartOutgoing(
                            this,
                            peerKey,
                            intent.getStringExtra(ExtraOutgoingName).orEmpty().ifBlank { login },
                            binding,
                        )
                    }
                },
            )
            if (!accepted) {
                finish()
                return false
            }
        }
        if (redial && existingCall) {
            incomingInvite = null
            outgoingCallKey = null
            outgoingLogin = null
            outgoingName = null
            outgoingContactAddress = null
            pendingOutgoingStart = false
            return true
        }
        if (outgoingCallKey != key) actionGate.reset()
        CallReplyResultStore(this).clearForNewCall(key)
        outgoingCallKey = key
        outgoingLogin = login
        outgoingName = intent.getStringExtra(ExtraOutgoingName).orEmpty().ifBlank { login }
        outgoingContactAddress = contactAddress
        incomingInvite = null
        pendingOutgoingStart = callState.callKey != key || callState.phase == CallPhase.Idle
        if (redial) {
            when (val started = requireNotNull(redialStart)) {
                OutgoingCallStartResult.Offline,
                OutgoingCallStartResult.Unavailable -> {
                    val message = if (started == OutgoingCallStartResult.Offline) {
                        "Нет подключения к интернету"
                    } else {
                        "Не удалось начать звонок"
                    }
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                    outgoingLogin = null
                    outgoingName = null
                    outgoingCallKey = null
                    pendingOutgoingStart = false
                    finish()
                    return false
                }
                is OutgoingCallStartResult.Started -> {
                    key = started.key
                    outgoingCallKey = key
                    intent.putExtra(ExtraOutgoingCallId, key.callId)
                }
                is OutgoingCallStartResult.Busy -> {
                    incomingInvite = incomingController.load(this)?.invite?.takeIf { it.owner == started.owner }
                    outgoingLogin = null
                    outgoingName = null
                    outgoingCallKey = null
                    pendingOutgoingStart = false
                }
            }
        }
        refreshReplyResult()
        return true
    }

    private fun acknowledgeMissedCall(peer: AccountPeerKey, session: Session) {
        val appContext = applicationContext
        val notifier = IncomingCallNotifier(appContext)
        val authStore = AuthStore(SharedPreferencesKeyValueStore(appContext), AndroidKeystoreTokenCipher())
        val accountRefreshId = authStore.withCurrent(peer.accountId, session) {
            notifier.beginAccountMissedCountRefresh(peer.accountId)
        } ?: return
        Thread {
            val repository = ContactRepository(authStore)
            val unread = runCatching {
                acknowledgeLatestMissedCall(
                    login = peer.login,
                    loadLatestId = { login ->
                        repository.loadCallHistory(
                            peer.accountId,
                            limit = 1,
                            peerLogin = login,
                            expectedSession = session,
                        )?.latestId
                    },
                    markRead = { login, throughId ->
                        repository.markCallHistoryRead(
                            peer.accountId,
                            throughId,
                            peerLogin = login,
                            expectedSession = session,
                        )?.unread
                    },
                )
            }.getOrNull() ?: return@Thread
            authStore.withCurrent(peer.accountId, session) {
                val update = notifier.updateAccountMissedState(
                    peer.accountId,
                    unread,
                    accountRefreshId,
                    redialBinding = CallSessionBinding.from(session),
                    immediate = true,
                )
                if (update.applied) {
                    CallHistoryEvents.publish(AccountUnreadState(peer.accountId, unread, session))
                }
            }
        }.start()
    }

    private fun visibleCallState(): CallUiState {
        replyResult?.takeIf { it.key == outgoingCallKey }?.let { result ->
            return CallUiState(accountId = result.key.accountId, callId = result.key.callId,
                peer = result.peer, direction = CallDirection.Outgoing, phase = CallPhase.Ended,
                endedAtElapsedMs = result.endedAtElapsedMs,
                endReason = CallEndReason.Rejected)
        }
        val invite = incomingInvite
        if (invite != null && callState.callKey != invite.key) {
            return CallUiState(
                accountId = invite.accountId,
                callId = invite.callId,
                peer = CallPeer(
                    invite.caller.ifBlank { "TiniTalk" },
                    invite.callerLogin,
                    invite.callerLogin?.let { ContactAddress.of(invite.sessionBinding.serverUrl, it) },
                ),
                direction = CallDirection.Incoming,
                phase = CallPhase.Ringing,
            )
        }
        val login = outgoingLogin
        val key = outgoingCallKey
        if (login != null && key != null) {
            if (callState.callKey == key && callState.phase != CallPhase.Idle) return callState
            return CallUiState(
                accountId = key.accountId,
                callId = key.callId,
                peer = CallPeer(outgoingName.orEmpty().ifBlank { login }, login, outgoingContactAddress),
                direction = CallDirection.Outgoing,
                phase = CallPhase.Connecting,
            )
        }
        return callState
    }

    private fun answer(invite: IncomingInvite) {
        if (!actionGate.lock(CallScreenAction.Answer, invite.key)) return
        incomingController.answer(this, invite)
    }

    private fun reject(invite: IncomingInvite, replyCode: CallReplyCode? = null) {
        if (!isCurrentIncoming(invite) || visibleCallState().phase != CallPhase.Ringing ||
            (replyCode != null && !replySupported)) return
        if (!actionGate.lock(CallScreenAction.Reject, invite.key)) return
        incomingController.reject(this, invite, replyCode)
    }

    private fun refreshReplyResult() {
        val key = outgoingCallKey
        val stored = CallReplyResultStore(this).load()?.takeIf { it.key == key }
        val liveOtherCall = callState.phase != CallPhase.Idle && callState.phase != CallPhase.Ended && callState.callKey != key
        replyResult = stored?.takeUnless { liveOtherCall }?.takeIf { result ->
            val binding = result.sessionBinding
            binding == null || resolvePinnedCallSession(
                AuthStore(SharedPreferencesKeyValueStore(this), AndroidKeystoreTokenCipher()),
                result.key.accountId, binding,
            ) != null
        }
        if (replyResult != null) pendingOutgoingStart = false
    }

    private fun bindCallSession() {
        val key = callState.callKey ?: return
        if (callSessionBinding != null) return
        callSessionBinding = incomingInvite?.takeIf { it.key == key }?.sessionBinding
            ?: GlobalCallAdmission.current()?.owner?.takeIf { it.key == key }?.sessionBinding
            ?: runCatching {
                AuthStore(SharedPreferencesKeyValueStore(this), AndroidKeystoreTokenCipher())
                    .get(key.accountId)?.session?.let(CallSessionBinding::from)
            }.getOrNull()
    }

    private fun validateEndedCallSession() {
        val state = visibleCallState().takeIf { it.phase == CallPhase.Ended } ?: return
        val key = state.callKey ?: return
        val binding = callSessionBinding ?: return
        val auth = AuthStore(SharedPreferencesKeyValueStore(this), AndroidKeystoreTokenCipher())
        if (!auth.matchesSessionIdentity(key.accountId, binding.serverUrl, binding.login, binding.sessionId, binding.configId)) {
            callState = CallUiState()
            finish()
        }
    }

    private fun endedScreenTimeoutMillis(): Long = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        getSystemService(AccessibilityManager::class.java).getRecommendedTimeoutMillis(
            EndedScreenMillis.toInt(), AccessibilityManager.FLAG_CONTENT_ICONS or AccessibilityManager.FLAG_CONTENT_TEXT,
        ).toLong().coerceAtLeast(EndedScreenMillis)
    } else EndedScreenMillis

    private fun endedScreenRemainingMillis(state: CallUiState, timeoutMillis: Long): Long {
        val endedAt = state.endedAtElapsedMs ?: return 0L
        val elapsed = SystemClock.elapsedRealtime() - endedAt
        // A future timestamp (for example after a reboot) cannot start a new reading period.
        return if (elapsed < 0L) 0L else (timeoutMillis - elapsed).coerceAtLeast(0L)
    }

    private fun updateEndedScreenTimeout() {
        handler.removeCallbacks(endedScreenTimeout)
        if (isFinishing) return
        val state = visibleCallState()
        if (state.phase != CallPhase.Ended) return
        val remainingMillis = endedScreenRemainingMillis(state, endedScreenTimeoutMillis())
        if (remainingMillis == 0L) {
            if (replyResult != null) dismissReplyResult() else finish()
        } else {
            // This timer keeps running when Compose stops rendering in the background.
            handler.postDelayed(endedScreenTimeout, remainingMillis)
        }
    }

    private fun dismissReplyResult() {
        replyResult?.let { CallReplyResultStore(this).clear(it.key) }
        replyResult = null
        finish()
        publishVisibleCall()
    }

    private fun publishVisibleCall() {
        val state = visibleCallState()
        val key = state.callKey?.takeIf {
            activityResumed && !isFinishing && state.direction == CallDirection.Outgoing &&
                state.phase != CallPhase.Idle && it == outgoingCallKey
        }
        CallScreenVisibility.update(visibilityToken, key)
    }

    private fun endCall(state: CallUiState) {
        val actionKey = incomingInvite?.key
            ?: outgoingCallKey
            ?: state.callKey
            ?: return
        if (!actionGate.lock(CallScreenAction.End, actionKey)) return
        CallForegroundService.end(this)
    }

    internal fun requestCamera() {
        cameraPermissionRouter.request(visibleCallState(), VideoCallStateStore.snapshot())
    }

    internal fun disableCamera() {
        val state = visibleCallState()
        val callKey = state.callKey ?: return
        if (state.phase == CallPhase.Active) {
            CallForegroundService.cameraRequested(this, callKey, requested = false)
        }
    }

    internal fun switchCamera() {
        val state = visibleCallState()
        val callKey = state.callKey ?: return
        if (state.phase == CallPhase.Active) CallForegroundService.switchCamera(this, callKey)
    }

    private fun publishCameraForeground(visible: Boolean) {
        val state = visibleCallState()
        val callKey = state.callKey ?: return
        if (state.phase != CallPhase.Active) return
        val permissionGranted = cameraPermissionGranted()
        if (!cameraForegroundPublicationGate.shouldPublish(callKey, visible, permissionGranted)) return
        CallForegroundService.cameraForeground(
            this,
            callKey,
            foreground = visible,
            permissionGranted = permissionGranted,
        )
    }

    private fun cameraPermissionGranted(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun cameraVisible(): Boolean =
        ::cameraForegroundLifecycle.isInitialized && cameraForegroundLifecycle.visible

    private fun screenInteractive(): Boolean =
        getSystemService(PowerManager::class.java).isInteractive

    private fun updateProximity() {
        if (!::proximityController.isInitialized) return
        val connected = callState.connectionHealth == ConnectionHealth.Good ||
            callState.connectionHealth == ConnectionHealth.Poor
        val earpiece = callState.currentAudioEndpoint?.type == CallEndpointCompat.TYPE_EARPIECE
        val outgoingDial = callState.direction == CallDirection.Outgoing &&
            (callState.phase == CallPhase.Connecting || callState.phase == CallPhase.Ringing)
        val activeConversation = callState.phase == CallPhase.Active &&
            callState.connectedAtElapsedMs != null && connected
        val videoVisible = renderedVideoCallKey == callState.callKey && renderedVideoVisible
        val showingScreen = videoState.callKey == callState.callKey &&
            (videoState.screen.requested || videoState.screen.remoteId != null)
        proximityController.setEnabled(
            activityStarted && !videoVisible && !showingScreen && earpiece && (outgoingDial || activeConversation),
        )
    }

    private fun updateRenderedVideoVisibility(callKey: AccountCallKey?, visible: Boolean) {
        val current = visibleCallState()
        if (callKey == null || current.callKey != callKey || current.phase != CallPhase.Active) return
        renderedVideoCallKey = callKey
        renderedVideoVisible = visible
        updateProximity()
    }

    private fun showIncomingCallFullScreen() {
        val invite = incomingInvite ?: return
        if (!isCurrentIncoming(invite)) return
        if (shouldDismissIncomingOverlay(activityStarted, visibleCallState())) {
            IncomingCallNotifier(this).fullScreenShown(invite)
        }
    }

    private fun restoreIncomingCallNotification() {
        val invite = incomingInvite ?: return
        val stillRinging = !actionGate.isLocked(invite.key) &&
            isCurrentIncoming(invite) &&
            visibleCallState().phase == CallPhase.Ringing
        if (stillRinging) IncomingCallNotifier(this).fullScreenHidden(invite)
    }

    private fun finishIncomingPresentation(invite: IncomingInvite) {
        val liveOtherCall = callState.phase != CallPhase.Idle && callState.phase != CallPhase.Ended &&
            callState.callKey != invite.key
        if (liveOtherCall) return
        val displayed = visibleCallState()
        if (displayed.callKey != invite.key || displayed.phase != CallPhase.Ringing) return
        val expired = !invite.expiresAt.isAfter(Instant.now())
        if (expired && java.time.Duration.between(invite.expiresAt, Instant.now()).toMillis() >= endedScreenTimeoutMillis()) {
            finish()
            return
        }
        if (!expired && !incomingController.isTerminal(this, invite.owner)) {
            finish()
            return
        }
        // Before answer, ringing exists only in this Activity, not in the call runtime.
        // Retain that peer locally so cancellation/expiry gets the common ended screen.
        callState = displayed.onEnded(
            if (expired) CallEndReason.TimedOut else CallEndReason.Cancelled,
            SystemClock.elapsedRealtime(),
        )
        callSessionBinding = invite.sessionBinding
        validateEndedCallSession()
        updateEndedScreenTimeout()
        publishVisibleCall()
        updateProximity()
        cameraForegroundLifecycle.onCallStateChanged()
    }

    private fun isCurrentIncoming(invite: IncomingInvite): Boolean {
        val displayedCallMatches = callState.callKey == invite.key &&
            (callState.phase == CallPhase.Active || callState.phase == CallPhase.Ended)
        if (displayedCallMatches) return true
        if (!invite.expiresAt.isAfter(Instant.now()) || incomingController.isTerminal(this, invite.owner)) {
            return false
        }
        val pending = incomingController.load(this)
        if (pending?.invite?.key == invite.key && pending.action == IncomingCallController.ActionReject) {
            return false
        }
        val storedCallMatches = pending?.invite?.key == invite.key
        val liveCallMatches = callState.callKey == invite.key &&
            callState.phase != CallPhase.Idle && callState.phase != CallPhase.Ended
        return storedCallMatches || liveCallMatches
    }

    companion object {
        private const val StateOutgoingCallIntent = "outgoing_call_intent"
        private const val StateEndedCall = "ended_call_state"
        private const val StatePresentedIncomingOwner = "presented_incoming_owner"
        private const val ExtraOutgoingLogin = "outgoing_login"
        private const val ExtraOutgoingName = "outgoing_name"
        private const val ExtraOutgoingAccountId = "outgoing_account_id"
        private const val ExtraOutgoingCallId = "outgoing_call_id"
        private const val ExtraOutgoingServerUrl = "outgoing_server_url"
        private const val ExtraRedialServerUrl = "redial_server_url"
        private const val ExtraRedialSessionLogin = "redial_session_login"
        private const val ExtraRedialSessionId = "redial_session_id"
        private const val ExtraRedialConfigId = "redial_config_id"
        private const val ActionRedial = "org.tinitalk.action.REDIAL"
        private const val StatePendingCameraCallId = "pending_camera_call_id"
        private const val StatePendingCameraAccountId = "pending_camera_account_id"
        private const val InviteCheckIntervalMillis = 500L
        private const val IdleGraceMillis = 1_000L
        private const val EndedScreenMillis = 3_000L

        fun outgoingIntent(
            context: Context,
            peer: AccountPeerKey,
            contactAddress: ContactAddress,
            displayName: String,
            callKey: AccountCallKey,
        ): Intent =
            Intent(context, CallActivity::class.java)
                .setData("tinitalk://outgoing/${android.net.Uri.encode(callKey.localId())}".toUri())
                .putExtra(ExtraOutgoingAccountId, peer.accountId.value)
                .putExtra(ExtraOutgoingCallId, callKey.callId)
                .putExtra(ExtraOutgoingLogin, peer.login)
                .putExtra(ExtraOutgoingServerUrl, contactAddress.serverUrl)
                .putExtra(ExtraOutgoingName, displayName)

        fun ongoingIntent(context: Context): Intent =
            Intent(context, CallActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        fun redialIntent(
            context: Context,
            peer: AccountPeerKey,
            displayName: String,
            binding: CallSessionBinding,
        ): Intent {
            val callKey = AccountCallKey(peer.accountId, UUID.randomUUID().toString())
            val owner = AccountCallOwner(callKey, binding)
            return outgoingIntent(
                context,
                peer,
                ContactAddress.of(binding.serverUrl, peer.login),
                displayName,
                callKey,
            )
                .setData("tinitalk://redial/${android.net.Uri.encode(owner.localId())}".toUri())
                .setAction(ActionRedial)
                .putExtra(ExtraRedialServerUrl, binding.serverUrl)
                .putExtra(ExtraRedialSessionLogin, binding.login)
                .putExtra(ExtraRedialSessionId, binding.sessionId)
                .putExtra(ExtraRedialConfigId, binding.configId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        private fun redialBindingFrom(intent: Intent): CallSessionBinding? {
            val serverUrl = intent.getStringExtra(ExtraRedialServerUrl)?.takeIf(String::isNotBlank) ?: return null
            val login = intent.getStringExtra(ExtraRedialSessionLogin)?.takeIf(String::isNotBlank) ?: return null
            val sessionId = intent.getStringExtra(ExtraRedialSessionId)?.takeIf(String::isNotBlank) ?: return null
            return CallSessionBinding(
                serverUrl,
                login,
                sessionId,
                intent.getStringExtra(ExtraRedialConfigId),
            )
        }

        private fun outgoingContactAddressFrom(intent: Intent, login: String): ContactAddress? =
            intent.getStringExtra(ExtraOutgoingServerUrl)
                ?.takeIf(String::isNotBlank)
                ?.let { serverUrl -> ContactAddress.of(normalizeServerUrl(serverUrl), login) }
    }
}

internal fun executePinnedRedial(
    authStore: AuthStore,
    peer: AccountPeerKey,
    binding: CallSessionBinding,
    acknowledge: (Session) -> Unit,
    start: () -> Unit,
): Boolean {
    val session = resolvePinnedCallSession(authStore, peer.accountId, binding) ?: return false
    acknowledge(session)
    start()
    return true
}

internal class CameraForegroundPublicationGate {
    private var published: CameraForegroundPublication? = null

    fun shouldPublish(callKey: AccountCallKey, foreground: Boolean, permissionGranted: Boolean): Boolean {
        val next = CameraForegroundPublication(callKey, foreground, permissionGranted)
        if (next == published) return false
        published = next
        return true
    }
}

internal class CallActivityCameraForeground(
    private val screenInteractive: () -> Boolean,
    private val retryPendingCamera: () -> Unit,
    private val publish: (Boolean) -> Unit,
) {
    private var resumed = false

    val visible: Boolean
        get() = resumed && screenInteractive()

    fun onResume() {
        resumed = true
        publishVisible()
    }

    fun onPause() {
        resumed = false
        publish(false)
    }

    fun onScreenOff() {
        publish(false)
    }

    fun onScreenOn() {
        if (resumed) publishVisible()
    }

    fun onUserPresent() {
        if (resumed) publishVisible()
    }

    fun onCallStateChanged() {
        if (resumed) publish(visible)
    }

    private fun publishVisible() {
        if (visible) retryPendingCamera()
        publish(visible)
    }
}

private data class CameraForegroundPublication(
    val callKey: AccountCallKey,
    val foreground: Boolean,
    val permissionGranted: Boolean,
)

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
