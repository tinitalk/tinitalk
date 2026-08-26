package org.tinitalk

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import org.tinitalk.call.CallAudioState
import org.tinitalk.call.CallPhase
import org.tinitalk.call.CallServiceState
import org.tinitalk.call.CallSnapshot
import org.tinitalk.call.CallUiState
import org.tinitalk.call.CallUiStateStore
import org.tinitalk.data.AndroidKeystoreTokenCipher
import org.tinitalk.data.ApiException
import org.tinitalk.data.AuthStore
import org.tinitalk.data.Contact
import org.tinitalk.data.ContactRepository
import org.tinitalk.data.SharedPreferencesKeyValueStore
import org.tinitalk.permissions.AppPermissionsState
import org.tinitalk.push.DeviceRegistrar
import org.tinitalk.push.IncomingCallNotifier
import org.tinitalk.push.IncomingInvite
import org.tinitalk.telecom.AudioEndpointState
import org.tinitalk.telecom.CallForegroundService
import org.tinitalk.telecom.IncomingCallController
import org.tinitalk.ui.CallPanelState
import org.tinitalk.ui.MainScreen
import org.tinitalk.ui.MainScreenState
import org.tinitalk.ui.theme.TiniTalkTheme
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant

class MainActivity : ComponentActivity() {
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { refreshPermissions() }
    private val microphonePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { refreshPermissions() }

    private lateinit var repository: ContactRepository
    private lateinit var authStore: AuthStore
    private val incomingController = IncomingCallController()
    private var screenState by mutableStateOf(MainScreenState())
    private var callSnapshot by mutableStateOf(CallSnapshot())
    private var callUiState by mutableStateOf(CallUiState())
    private var audioEndpoints by mutableStateOf(AudioEndpointState())
    private var incomingInvite by mutableStateOf<IncomingInvite?>(null)
    private var loginResetKey by mutableIntStateOf(0)
    private var pushRegistrationStarted = false
    private var handledIncomingAction: String? = null

    private val callObserver: (CallSnapshot) -> Unit = { snapshot ->
        runOnUiThread {
            callSnapshot = snapshot
            if (snapshot.phase == CallPhase.Ended) incomingInvite = null
        }
    }
    private val callUiObserver: (CallUiState) -> Unit = { state ->
        runOnUiThread { callUiState = state }
    }
    private val audioEndpointObserver: (AudioEndpointState) -> Unit = { state ->
        runOnUiThread { audioEndpoints = state }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        authStore = AuthStore(SharedPreferencesKeyValueStore(this), AndroidKeystoreTokenCipher())
        repository = ContactRepository(authStore)
        callSnapshot = CallServiceState.snapshot()
        callUiState = CallUiStateStore.snapshot()
        audioEndpoints = CallAudioState.snapshot()

        setContent {
            TiniTalkTheme {
                val callPanel = currentCallPanel()
                SideEffect {
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = callPanel == null
                        isAppearanceLightNavigationBars = callPanel == null
                    }
                }
                MainScreen(
                    state = screenState,
                    call = callPanel,
                    loginResetKey = loginResetKey,
                    onSignIn = ::loadContacts,
                    onRequestNotifications = ::requestNotificationPermission,
                    onRequestMicrophone = ::requestMicrophonePermission,
                    onRequestFullScreenCalls = ::requestFullScreenIntentPermission,
                    onRefreshPermissions = ::refreshPermissions,
                    onCall = { contact ->
                        CallForegroundService.startOutgoing(this, contact.login, contact.displayName)
                    },
                    onSignOut = ::signOut,
                    onAnswer = { incomingInvite?.let { incomingController.answer(this, it) } },
                    onReject = { incomingInvite?.let { incomingController.reject(this, it) } },
                    onEndCall = { CallForegroundService.end(this) },
                    onMute = { muted -> CallForegroundService.mute(this, muted) },
                    onSelectEndpoint = { endpoint ->
                        callSnapshot.callId?.let { callId ->
                            CallForegroundService.selectAudioEndpoint(this, callId, endpoint.id)
                        }
                    },
                )
            }
        }

        CallServiceState.observe(callObserver)
        CallUiStateStore.observe(callUiObserver)
        CallAudioState.observe(audioEndpointObserver)
        refreshPermissions()
        restoreContacts()
    }

    private fun currentCallPanel(): CallPanelState? {
        if (!screenState.signedIn || !screenState.permissions.allRequiredGranted) return null
        val invite = incomingInvite?.takeIf { it.expiresAt.isAfter(Instant.now()) }
        val phase = if (callSnapshot.phase == CallPhase.Idle && invite != null) {
            CallPhase.Ringing
        } else {
            callSnapshot.phase
        }
        if (phase == CallPhase.Idle) return null
        return CallPanelState(
            phase = phase,
            peerName = invite?.caller?.ifEmpty { "TiniTalk" }
                ?: callUiState.peer?.displayName
                ?: "TiniTalk",
            muted = callUiState.muted,
            currentEndpoint = audioEndpoints.current,
            availableEndpoints = audioEndpoints.available,
        )
    }

    private fun restoreContacts() {
        Thread {
            runCatching { repository.restoreContacts() }
                .onSuccess { contacts ->
                    runOnUiThread {
                        if (contacts == null) {
                            screenState = MainScreenState(
                                restoring = false,
                                permissions = screenState.permissions,
                            )
                        } else {
                            showContacts(contacts)
                        }
                    }
                }
                .onFailure(::showError)
        }.start()
    }

    private fun loadContacts(url: String, login: String, token: String) {
        screenState = screenState.copy(signingIn = true, errorMessage = null)
        Thread {
            runCatching { repository.signIn(url, login, token) }
                .onSuccess(::showContacts)
                .onFailure(::showError)
        }.start()
    }

    private fun showContacts(contacts: List<Contact>) {
        runOnUiThread {
            screenState = screenState.copy(
                restoring = false,
                signingIn = false,
                signedIn = true,
                contacts = contacts,
                errorMessage = null,
            )
            refreshPermissions()
        }
    }

    private fun showError(error: Throwable) {
        val message = when (error) {
            is ApiException -> when (error.code) {
                401 -> "Неверный логин или токен"
                404 -> "Сервер TiniTalk не найден"
                else -> "Сервер вернул ошибку ${error.code}"
            }
            is UnknownHostException -> "Сервер не найден. Проверьте адрес и подключение к сети"
            is SocketTimeoutException -> "Сервер не отвечает. Попробуйте ещё раз"
            is MalformedURLException -> "Проверьте адрес сервера"
            else -> "Не удалось подключиться к серверу"
        }
        runOnUiThread {
            screenState = screenState.copy(
                restoring = false,
                signingIn = false,
                signedIn = false,
                errorMessage = message,
            )
        }
    }

    private fun signOut() {
        repository.signOut()
        incomingInvite = null
        handledIncomingAction = null
        pushRegistrationStarted = false
        loginResetKey++
        screenState = MainScreenState(
            restoring = false,
            permissions = screenState.permissions,
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (screenState.permissions.allRequiredGranted) handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        val pending = incomingController.load(this)
        val invite = IncomingCallController.inviteFrom(intent) ?: pending?.invite ?: return
        if (!invite.expiresAt.isAfter(Instant.now())) {
            incomingController.clear(this, invite.callId)
            IncomingCallNotifier(this).cancel()
            if (incomingInvite?.callId == invite.callId) incomingInvite = null
            return
        }
        incomingInvite = invite
        val action = intent?.action ?: pending?.action ?: IncomingCallController.ActionIncoming
        val actionKey = "${invite.callId}:$action"
        if (handledIncomingAction == actionKey) return
        handledIncomingAction = actionKey
        when (action) {
            IncomingCallController.ActionAnswer -> incomingController.answer(this, invite)
            IncomingCallController.ActionReject -> incomingController.reject(this, invite)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
    }

    override fun onDestroy() {
        CallServiceState.removeObserver(callObserver)
        CallUiStateStore.removeObserver(callUiObserver)
        CallAudioState.removeObserver(audioEndpointObserver)
        super.onDestroy()
    }

    private fun refreshPermissions() {
        val notificationsGranted =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val microphoneGranted =
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val fullScreenIntentGranted =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
                getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

        val permissions = AppPermissionsState(
            notificationsGranted = notificationsGranted,
            microphoneGranted = microphoneGranted,
            fullScreenIntentGranted = fullScreenIntentGranted,
        )
        screenState = screenState.copy(permissions = permissions)
        if (screenState.signedIn && permissions.allRequiredGranted) {
            registerPushToken()
            handleIncomingIntent(intent)
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            refreshPermissions()
        }
    }

    private fun requestMicrophonePermission() {
        microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun requestFullScreenIntentPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            refreshPermissions()
            return
        }
        val notificationManager = getSystemService(NotificationManager::class.java)
        if (notificationManager.canUseFullScreenIntent()) {
            refreshPermissions()
            return
        }
        runCatching {
            startActivity(
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                    .setData(Uri.parse("package:$packageName")),
            )
        }
    }

    private fun registerPushToken() {
        if (pushRegistrationStarted || !screenState.permissions.allRequiredGranted) return
        val session = authStore.load() ?: return
        pushRegistrationStarted = true
        DeviceRegistrar.forSession(this, session).register(DeviceRegistrar.deviceId(this))
    }
}
