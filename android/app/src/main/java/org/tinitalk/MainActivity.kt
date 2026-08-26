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
    private var historyLoadGeneration = 0
    private val callUiObserver: (CallUiState) -> Unit = { state ->
        runOnUiThread { callUiState = state }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        authStore = AuthStore(SharedPreferencesKeyValueStore(this), AndroidKeystoreTokenCipher())
        repository = ContactRepository(authStore)
        setContent {
            TiniTalkTheme(darkTheme = true) {
                SideEffect {
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = false
                        isAppearanceLightNavigationBars = false
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
                    onHistoryVisible = ::showHistory,
                    onLoadMoreHistory = ::loadMoreHistory,
                    onRetryHistory = ::retryHistory,
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
            historyLoadGeneration++
            screenState = screenState.copy(
                restoring = false,
                signingIn = false,
                signedIn = true,
                contacts = contacts,
                history = emptyList(),
                historyLoaded = false,
                historyLoading = false,
                historyLoadingMore = false,
                historyNextBefore = 0,
                historyLatestId = 0,
                historyErrorMessage = null,
                unreadMissedCount = 0,
                errorMessage = null,
            )
            refreshPermissions()
        }
    }

    private fun showHistory() {
        loadHistory(reset = true)
    }

    private fun loadMoreHistory() {
        loadHistory(reset = false)
    }

    private fun retryHistory() {
        loadHistory(reset = screenState.history.isEmpty() || screenState.historyNextBefore == 0L)
    }

    private fun loadHistory(reset: Boolean) {
        if (!screenState.signedIn) return
        val before: Long
        val generation: Int
        if (reset) {
            if (screenState.historyLoading) return
            historyLoadGeneration++
            generation = historyLoadGeneration
            before = 0
            screenState = screenState.copy(historyLoading = true, historyErrorMessage = null)
        } else {
            before = screenState.historyNextBefore
            if (before == 0L || screenState.historyLoading || screenState.historyLoadingMore) return
            generation = historyLoadGeneration
            screenState = screenState.copy(historyLoadingMore = true, historyErrorMessage = null)
        }
        Thread {
            runCatching { repository.loadCallHistory(before = before) }
                .onSuccess { page ->
                    if (page == null) return@onSuccess
                    runOnUiThread {
                        if (!screenState.signedIn || generation != historyLoadGeneration) return@runOnUiThread
                        val combined = if (reset) {
                            page.items
                        } else {
                            (screenState.history + page.items).distinctBy { it.id }
                        }
                        screenState = screenState.copy(
                            history = combined,
                            historyLoaded = true,
                            historyLoading = false,
                            historyLoadingMore = false,
                            historyNextBefore = page.nextBefore,
                            historyLatestId = page.latestId,
                            historyErrorMessage = null,
                            unreadMissedCount = page.unreadMissedCount,
                        )
                    }
                    if (reset && page.latestId > 0) {
                        runCatching { repository.markCallHistoryRead(page.latestId) }
                            .onSuccess {
                                runOnUiThread {
                                    if (generation == historyLoadGeneration) {
                                        screenState = screenState.copy(unreadMissedCount = 0)
                                    }
                                }
                            }
                            .onFailure { if (it is ApiException && it.code == 401) showError(it) }
                    }
                }
                .onFailure { error ->
                    if (error is ApiException && error.code == 401) {
                        showError(error)
                    } else {
                        runOnUiThread {
                            if (generation != historyLoadGeneration) return@runOnUiThread
                            screenState = screenState.copy(
                                historyLoaded = true,
                                historyLoading = false,
                                historyLoadingMore = false,
                                historyErrorMessage = "Не удалось загрузить историю. Проверьте соединение.",
                            )
                        }
                    }
                }
        }.start()
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
        historyLoadGeneration++
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
