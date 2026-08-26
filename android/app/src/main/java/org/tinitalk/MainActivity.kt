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
import org.tinitalk.call.CallPhase
import org.tinitalk.call.CallServiceState
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
import org.tinitalk.telecom.CallForegroundService
import org.tinitalk.ui.MainScreen
import org.tinitalk.ui.MainScreenState
import org.tinitalk.ui.theme.TiniTalkTheme
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class MainActivity : ComponentActivity() {
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { refreshPermissions() }
    private val microphonePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { refreshPermissions() }

    private lateinit var repository: ContactRepository
    private lateinit var authStore: AuthStore
    private var screenState by mutableStateOf(MainScreenState())
    private var callUiState by mutableStateOf(CallUiStateStore.snapshot())
    private var loginResetKey by mutableIntStateOf(0)
    private var pushRegistrationStarted = false
    private val callUiObserver: (CallUiState) -> Unit = { state ->
        runOnUiThread { callUiState = state }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        authStore = AuthStore(SharedPreferencesKeyValueStore(this), AndroidKeystoreTokenCipher())
        repository = ContactRepository(authStore)
        setContent {
            TiniTalkTheme {
                SideEffect {
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = true
                        isAppearanceLightNavigationBars = true
                    }
                }
                MainScreen(
                    state = screenState,
                    ongoingCall = callUiState.takeIf {
                        it.phase != CallPhase.Idle && it.phase != CallPhase.Ended
                    },
                    loginResetKey = loginResetKey,
                    defaultServerUrl = BuildConfig.SERVER_URL,
                    onSignIn = ::loadContacts,
                    onRequestNotifications = ::requestNotificationPermission,
                    onRequestMicrophone = ::requestMicrophonePermission,
                    onRequestFullScreenCalls = ::requestFullScreenIntentPermission,
                    onRefreshPermissions = ::refreshPermissions,
                    onCall = ::startCall,
                    onOpenCall = { startActivity(CallActivity.ongoingIntent(this)) },
                    onSignOut = ::signOut,
                )
            }
        }
        CallUiStateStore.observe(callUiObserver)
        refreshPermissions()
        restoreContacts()
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

    private fun startCall(contact: Contact) {
        val currentCall = CallServiceState.snapshot()
        if (currentCall.phase != CallPhase.Idle && currentCall.phase != CallPhase.Ended) {
            startActivity(CallActivity.ongoingIntent(this))
            return
        }
        CallForegroundService.startOutgoing(this, contact.login, contact.displayName)
        startActivity(CallActivity.outgoingIntent(this, contact.login, contact.displayName))
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
        pushRegistrationStarted = false
        loginResetKey++
        screenState = MainScreenState(
            restoring = false,
            permissions = screenState.permissions,
        )
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
    }

    override fun onDestroy() {
        CallUiStateStore.removeObserver(callUiObserver)
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
