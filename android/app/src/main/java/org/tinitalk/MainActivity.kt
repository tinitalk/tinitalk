package org.tinitalk

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
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
import org.tinitalk.data.AuthSessionEvent
import org.tinitalk.data.AuthSessionEvents
import org.tinitalk.data.AuthStore
import org.tinitalk.data.Contact
import org.tinitalk.data.ContactPage
import org.tinitalk.data.ContactRepository
import org.tinitalk.data.CallUnreadState
import org.tinitalk.data.CompatibilityProblem
import org.tinitalk.data.ServerCompatibilityException
import org.tinitalk.data.SessionReplacedReason
import org.tinitalk.data.SharedPreferencesKeyValueStore
import org.tinitalk.network.NetworkAvailability
import org.tinitalk.network.networkAvailability
import org.tinitalk.permissions.AppPermissionsState
import org.tinitalk.push.DeviceRegistrar
import org.tinitalk.push.IncomingCallNotifier
import org.tinitalk.telecom.CallForegroundService
import org.tinitalk.ui.MainScreen
import org.tinitalk.ui.MainScreenState
import org.tinitalk.ui.ContactNameViewModel
import org.tinitalk.ui.ContactHistoryState
import org.tinitalk.ui.isCurrentContactHistoryRequest
import org.tinitalk.ui.isCurrentSessionRequest
import org.tinitalk.ui.withContactsPage
import org.tinitalk.ui.withOfflineSession
import org.tinitalk.ui.withRefreshedContacts
import org.tinitalk.ui.withUnreadMissedState
import org.tinitalk.ui.withPage
import org.tinitalk.ui.theme.TiniTalkTheme
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

private const val SessionReplacedMessage = "Вход выполнен на другом устройстве"

class MainActivity : ComponentActivity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val contactNameViewModel by viewModels<ContactNameViewModel>()
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { refreshPermissions() }
    private val microphonePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { refreshPermissions() }

    private lateinit var repository: ContactRepository
    private lateinit var authStore: AuthStore
    private lateinit var network: NetworkAvailability
    private var screenState by mutableStateOf(MainScreenState())
    private var callUiState by mutableStateOf(CallUiStateStore.snapshot())
    private var loginResetKey by mutableIntStateOf(0)
    private var pushRegistrationStarted = false
    private var historyLoadGeneration = 0
    private var historyVisible = false
    private var contactHistoryGeneration = 0
    private var contactHistoryLogin: String? = null
    private var authGeneration = 0
    private val callUiObserver: (CallUiState) -> Unit = { state ->
        runOnUiThread { callUiState = state }
    }
    private val missedCountObserver: (Int) -> Unit = { count ->
        runOnUiThread {
            if (!isDestroyed && screenState.signedIn && screenState.unreadMissedCount != count) {
                screenState = screenState.copy(unreadMissedCount = count)
            }
        }
    }
    private val authSessionObserver: (AuthSessionEvent) -> Unit = {
        mainHandler.post {
            if (!isDestroyed && authStore.load() == null) resetToLogin(SessionReplacedMessage)
        }
    }
    private val networkObserver: (Boolean) -> Unit = { available ->
        mainHandler.post {
            if (!isDestroyed) updateNetworkAvailability(available)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        authStore = AuthStore(SharedPreferencesKeyValueStore(this), AndroidKeystoreTokenCipher())
        repository = ContactRepository(authStore)
        network = networkAvailability()
        screenState = screenState.copy(networkAvailable = network.available)
        setContent {
            TiniTalkTheme(darkTheme = true) {
                val contactNameUpdate = contactNameViewModel.state
                val visibleScreenState = screenState.withContactUpdates(contactNameViewModel.updatedContacts)
                LaunchedEffect(contactNameUpdate.authExpired) {
                    if (contactNameUpdate.authExpired) {
                        contactNameViewModel.reset()
                        showError(ApiException(401, "unauthorized", contactNameUpdate.authReason))
                    }
                }
                SideEffect {
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = false
                        isAppearanceLightNavigationBars = false
                    }
                }
                MainScreen(
                    state = visibleScreenState,
                    contactNameUpdate = contactNameUpdate,
                    ongoingCall = callUiState.takeIf {
                        it.phase != CallPhase.Idle && it.phase != CallPhase.Ended
                    },
                    loginResetKey = loginResetKey,
                    defaultServerUrl = BuildConfig.SERVER_URL,
                    onSignIn = ::loadContacts,
                    onCheckServer = repository::checkServer,
                    onCheckServerDetails = repository::checkServerDetails,
                    onRequestNotifications = ::requestNotificationPermission,
                    onRequestMicrophone = ::requestMicrophonePermission,
                    onRequestFullScreenCalls = ::requestFullScreenIntentPermission,
                    onRefreshPermissions = ::refreshPermissions,
                    onCall = ::startCall,
                    onRenameContact = { login, customName ->
                        if (network.available) {
                            contactNameViewModel.rename(repository, login, customName)
                        } else {
                            showNoInternetMessage()
                        }
                    },
                    onRenameHandled = contactNameViewModel::clearResult,
                    onOpenCall = { startActivity(CallActivity.ongoingIntent(this)) },
                    onContactsVisible = { historyVisible = false },
                    onRefreshContacts = ::refreshContacts,
                    onLoadMoreContacts = ::loadMoreContacts,
                    onContactsRefreshMessageHandled = ::clearContactsRefreshMessage,
                    onHistoryVisible = ::showHistory,
                    onLoadMoreHistory = ::loadMoreHistory,
                    onRetryHistory = ::retryHistory,
                    onContactHistoryVisible = ::showContactHistory,
                    onContactHistoryHidden = ::hideContactHistory,
                    onLoadMoreContactHistory = ::loadMoreContactHistory,
                    onRetryContactHistory = ::retryContactHistory,
                    onSignOut = ::signOut,
                )
            }
        }
        CallUiStateStore.observe(callUiObserver)
        IncomingCallNotifier(this).observeMissedCount(missedCountObserver)
        AuthSessionEvents.observe(authSessionObserver)
        network.observe(networkObserver)
        refreshPermissions()
        if (network.available) {
            restoreContacts()
        } else {
            screenState = screenState.withOfflineSession(authStore.load()?.url)
        }
    }

    private fun restoreContacts() {
        if (!network.available) {
            screenState = screenState.withOfflineSession(authStore.load()?.url)
            return
        }
        val requestAuthGeneration = authGeneration
        val deviceId = DeviceRegistrar.deviceId(this)
        Thread {
            runCatching {
                repository.restoreContacts(deviceId)?.let { contacts ->
                    contacts to authStore.load()?.url.orEmpty()
                }
            }
                .onSuccess { restored ->
                    runOnUiThread {
                        if (!isCurrentSessionRequest(requestAuthGeneration, authGeneration)) {
                            return@runOnUiThread
                        }
                        if (restored == null) {
                            screenState = MainScreenState(
                                restoring = false,
                                permissions = screenState.permissions,
                                networkAvailable = network.available,
                            )
                        } else {
                            showContacts(restored.first, restored.second)
                        }
                    }
                }
                .onFailure { showRestoreErrorIfCurrent(it, requestAuthGeneration) }
        }.start()
    }

    private fun loadContacts(url: String, login: String, token: String) {
        if (!network.available) {
            showNoInternetMessage()
            return
        }
        contactNameViewModel.reset()
        authGeneration++
        val requestAuthGeneration = authGeneration
        screenState = screenState.copy(signingIn = true, errorMessage = null)
        val serverUrl = url.trim().trimEnd('/')
        val deviceId = DeviceRegistrar.deviceId(this)
        Thread {
            runCatching { repository.signIn(url, login, token, deviceId) }
                .onSuccess { page ->
                    runOnUiThread {
                        if (isCurrentSessionRequest(requestAuthGeneration, authGeneration)) {
                            showContacts(page, serverUrl)
                        }
                    }
                }
                .onFailure { showSessionErrorIfCurrent(it, requestAuthGeneration) }
        }.start()
    }

    private fun startCall(contact: Contact) {
        val currentCall = CallServiceState.snapshot()
        if (currentCall.phase != CallPhase.Idle && currentCall.phase != CallPhase.Ended) {
            startActivity(CallActivity.ongoingIntent(this))
            return
        }
        if (!CallForegroundService.startOutgoing(this, contact.login, contact.displayName)) {
            showNoInternetMessage()
            return
        }
        startActivity(CallActivity.outgoingIntent(this, contact.login, contact.displayName))
    }

    private fun showContacts(page: ContactPage, serverUrl: String) {
        runOnUiThread {
            pushRegistrationStarted = false
            authGeneration++
            historyLoadGeneration++
            contactHistoryGeneration++
            contactHistoryLogin = null
            screenState = screenState.copy(
                restoring = false,
                signingIn = false,
                signedIn = true,
                serverUrl = serverUrl,
                contacts = page.items,
                contactsRefreshing = false,
                contactsRefreshErrorMessage = null,
                contactsLoadingMore = false,
                contactsNextCursor = page.nextCursor,
                contactsLoadMoreErrorMessage = null,
                history = emptyList(),
                historyLoaded = false,
                historyLoading = false,
                historyLoadingMore = false,
                historyNextBefore = 0,
                historyLatestId = 0,
                historyErrorMessage = null,
                contactHistory = ContactHistoryState(),
                unreadMissedCount = 0,
                latestUnreadMissedByContact = emptyMap(),
                errorMessage = null,
            )
            refreshPermissions()
            refreshMissedCount()
        }
    }

    private fun refreshContacts() {
        if (!network.available || !screenState.signedIn || screenState.contactsRefreshing || screenState.contactsLoadingMore) return
        val requestAuthGeneration = authGeneration
        screenState = screenState.copy(
            contactsRefreshing = true,
            contactsRefreshErrorMessage = null,
            contactsLoadMoreErrorMessage = null,
        )
        Thread {
            runCatching { repository.refreshContacts() }
                .onSuccess { page ->
                    runOnUiThread {
                        if (!screenState.signedIn ||
                            !isCurrentSessionRequest(requestAuthGeneration, authGeneration)
                        ) {
                            return@runOnUiThread
                        }
                        screenState = if (page == null) {
                            screenState.copy(
                                contactsRefreshing = false,
                                contactsRefreshErrorMessage = "Не удалось обновить контакты",
                            )
                        } else {
                            screenState.withRefreshedContacts(page)
                        }
                    }
                }
                .onFailure { error ->
                    if (error is ApiException && error.code == 401) {
                        showSessionErrorIfCurrent(error, requestAuthGeneration)
                    } else {
                        runOnUiThread {
                            if (!isCurrentSessionRequest(requestAuthGeneration, authGeneration)) {
                                return@runOnUiThread
                            }
                            screenState = screenState.copy(
                                contactsRefreshing = false,
                                contactsRefreshErrorMessage = "Не удалось обновить контакты",
                            )
                        }
                    }
                }
        }.start()
    }

    private fun loadMoreContacts() {
        if (!network.available || !screenState.signedIn || screenState.contactsRefreshing || screenState.contactsLoadingMore) return
        val cursor = screenState.contactsNextCursor
        if (cursor.isEmpty()) return
        val requestAuthGeneration = authGeneration
        screenState = screenState.copy(
            contactsLoadingMore = true,
            contactsLoadMoreErrorMessage = null,
        )
        Thread {
            runCatching { repository.refreshContacts(cursor) }
                .onSuccess { page ->
                    runOnUiThread {
                        if (!screenState.signedIn ||
                            !isCurrentSessionRequest(requestAuthGeneration, authGeneration)
                        ) {
                            return@runOnUiThread
                        }
                        screenState = if (page == null) {
                            screenState.copy(
                                contactsLoadingMore = false,
                                contactsLoadMoreErrorMessage = "Не удалось загрузить контакты",
                            )
                        } else {
                            screenState.withContactsPage(page)
                        }
                    }
                }
                .onFailure { error ->
                    if (error is ApiException && error.code == 401) {
                        showSessionErrorIfCurrent(error, requestAuthGeneration)
                    } else {
                        runOnUiThread {
                            if (!isCurrentSessionRequest(requestAuthGeneration, authGeneration)) {
                                return@runOnUiThread
                            }
                            screenState = screenState.copy(
                                contactsLoadingMore = false,
                                contactsLoadMoreErrorMessage = "Не удалось загрузить контакты",
                            )
                        }
                    }
                }
        }.start()
    }

    private fun clearContactsRefreshMessage() {
        screenState = screenState.copy(contactsRefreshErrorMessage = null)
    }

    private fun showHistory() {
        historyVisible = true
        loadHistory(reset = true)
    }

    private fun loadMoreHistory() {
        loadHistory(reset = false)
    }

    private fun retryHistory() {
        loadHistory(reset = screenState.history.isEmpty() || screenState.historyNextBefore == 0L)
    }

    private fun loadHistory(reset: Boolean) {
        if (!network.available || !screenState.signedIn) return
        val requestAuthGeneration = authGeneration
        val before: Long
        val generation: Int
        val badgeRefreshId: Long
        if (reset) {
            if (screenState.historyLoading) return
            historyLoadGeneration++
            generation = historyLoadGeneration
            before = 0
            badgeRefreshId = IncomingCallNotifier(this).beginMissedCountRefresh()
            screenState = screenState.copy(historyLoading = true, historyErrorMessage = null)
        } else {
            before = screenState.historyNextBefore
            if (before == 0L || screenState.historyLoading || screenState.historyLoadingMore) return
            generation = historyLoadGeneration
            badgeRefreshId = IncomingCallNotifier(this).beginMissedCountRefresh()
            screenState = screenState.copy(historyLoadingMore = true, historyErrorMessage = null)
        }
        Thread {
            runCatching { repository.loadCallHistory(before = before) }
                .onSuccess { page ->
                    if (page == null) return@onSuccess
                    runOnUiThread {
                        if (!screenState.signedIn || generation != historyLoadGeneration) return@runOnUiThread
                        applyUnreadMissedState(
                            CallUnreadState(page.unreadMissedCount, page.unreadMissed),
                            badgeRefreshId,
                        )
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
                        )
                    }
                    if (reset && page.latestId > 0) {
                        val readRefreshId = IncomingCallNotifier(this).beginMissedCountRefresh()
                        runCatching { repository.markCallHistoryRead(page.latestId) }
                            .onSuccess { unread ->
                                if (unread == null) return@onSuccess
                                runOnUiThread {
                                    if (generation == historyLoadGeneration) {
                                        applyUnreadMissedState(unread, readRefreshId)
                                    }
                                }
                            }
                            .onFailure {
                                if (it is ApiException && it.code == 401) {
                                    showSessionErrorIfCurrent(it, requestAuthGeneration)
                                }
                            }
                    }
                }
                .onFailure { error ->
                    if (error is ApiException && error.code == 401) {
                        showSessionErrorIfCurrent(error, requestAuthGeneration)
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

    private fun showContactHistory(login: String) {
        historyVisible = false
        if (contactHistoryLogin == login &&
            screenState.contactHistory.peerLogin == login &&
            (screenState.contactHistory.loaded || screenState.contactHistory.loading)
        ) {
            return
        }
        contactHistoryLogin = login
        loadContactHistory(login, reset = true)
    }

    private fun hideContactHistory() {
        if (contactHistoryLogin == null && screenState.contactHistory.peerLogin == null) return
        contactHistoryLogin = null
        contactHistoryGeneration++
        screenState = screenState.copy(contactHistory = ContactHistoryState())
    }

    private fun loadMoreContactHistory() {
        contactHistoryLogin?.let { loadContactHistory(it, reset = false) }
    }

    private fun retryContactHistory() {
        val login = contactHistoryLogin ?: return
        val history = screenState.contactHistory
        loadContactHistory(login, reset = history.items.isEmpty() || history.nextBefore == 0L)
    }

    private fun loadContactHistory(login: String, reset: Boolean) {
        if (!network.available || !screenState.signedIn || contactHistoryLogin != login) return
        val before: Long
        val generation: Int
        val requestAuthGeneration = authGeneration
        if (reset) {
            if (screenState.contactHistory.peerLogin == login && screenState.contactHistory.loading) return
            contactHistoryGeneration++
            generation = contactHistoryGeneration
            before = 0
            screenState = screenState.copy(
                contactHistory = ContactHistoryState(peerLogin = login, loading = true),
            )
        } else {
            val history = screenState.contactHistory
            before = history.nextBefore
            if (history.peerLogin != login || before == 0L || history.loading || history.loadingMore) return
            generation = contactHistoryGeneration
            screenState = screenState.copy(
                contactHistory = history.copy(loadingMore = true, errorMessage = null),
            )
        }
        val badgeRefreshId = IncomingCallNotifier(this).beginMissedCountRefresh()
        Thread {
            runCatching { repository.loadCallHistory(before = before, peerLogin = login) }
                .onSuccess { page ->
                    if (page == null) return@onSuccess
                    runOnUiThread {
                        if (!screenState.signedIn ||
                            !isCurrentSessionRequest(requestAuthGeneration, authGeneration) ||
                            !isCurrentContactHistoryRequest(
                                generation,
                                contactHistoryGeneration,
                                login,
                                contactHistoryLogin,
                            )
                        ) {
                            return@runOnUiThread
                        }
                        applyUnreadMissedState(
                            CallUnreadState(page.unreadMissedCount, page.unreadMissed),
                            badgeRefreshId,
                        )
                        screenState = screenState.copy(
                            contactHistory = screenState.contactHistory.withPage(login, page, reset),
                        )
                        if (reset && page.latestId > 0) {
                            markContactHistoryRead(login, page.latestId, generation, requestAuthGeneration)
                        }
                    }
                }
                .onFailure { error ->
                    if (error is ApiException && error.code == 401) {
                        showSessionErrorIfCurrent(error, requestAuthGeneration)
                    } else {
                        runOnUiThread {
                            if (!isCurrentSessionRequest(requestAuthGeneration, authGeneration) ||
                                !isCurrentContactHistoryRequest(
                                    generation,
                                    contactHistoryGeneration,
                                    login,
                                    contactHistoryLogin,
                                )
                            ) {
                                return@runOnUiThread
                            }
                            screenState = screenState.copy(
                                contactHistory = screenState.contactHistory.copy(
                                    loaded = true,
                                    loading = false,
                                    loadingMore = false,
                                    errorMessage = "Не удалось загрузить звонки. Проверьте соединение.",
                                ),
                            )
                        }
                    }
                }
        }.start()
    }

    private fun markContactHistoryRead(
        login: String,
        throughId: Long,
        generation: Int,
        requestAuthGeneration: Int,
    ) {
        if (!network.available ||
            !screenState.signedIn ||
            !isCurrentSessionRequest(requestAuthGeneration, authGeneration) ||
            !isCurrentContactHistoryRequest(
                generation,
                contactHistoryGeneration,
                login,
                contactHistoryLogin,
            )
        ) {
            return
        }
        val badgeRefreshId = IncomingCallNotifier(this).beginMissedCountRefresh()
        Thread {
            runCatching { repository.markCallHistoryRead(throughId, peerLogin = login) }
                .onSuccess { unread ->
                    if (unread == null) return@onSuccess
                    runOnUiThread {
                        if (!screenState.signedIn ||
                            !isCurrentSessionRequest(requestAuthGeneration, authGeneration)
                        ) {
                            return@runOnUiThread
                        }
                        applyUnreadMissedState(unread, badgeRefreshId)
                    }
                }
                .onFailure {
                    if (it is ApiException && it.code == 401) {
                        showSessionErrorIfCurrent(it, requestAuthGeneration)
                    }
                }
        }.start()
    }

    private fun showSessionErrorIfCurrent(
        error: Throwable,
        requestAuthGeneration: Int,
    ) {
        runOnUiThread {
            if (!isCurrentSessionRequest(requestAuthGeneration, authGeneration)) {
                return@runOnUiThread
            }
            showError(error)
        }
    }

    private fun showRestoreErrorIfCurrent(error: Throwable, requestAuthGeneration: Int) {
        runOnUiThread {
            if (!isCurrentSessionRequest(requestAuthGeneration, authGeneration)) return@runOnUiThread
            val session = authStore.load()
            val terminal = (error is ApiException && error.code == 401) ||
                (error is ServerCompatibilityException && error.problem != CompatibilityProblem.Unavailable) ||
                error is MalformedURLException
            if (session == null || terminal) {
                showError(error)
                return@runOnUiThread
            }
            screenState = if (!network.available) {
                screenState.withOfflineSession(session.url)
            } else {
                screenState.copy(
                    restoring = false,
                    signingIn = false,
                    signedIn = true,
                    serverUrl = session.url,
                    contactsRefreshErrorMessage = "Сервер TiniTalk временно недоступен",
                )
            }
        }
    }

    private fun showError(error: Throwable) {
        val message = when (error) {
            is ServerCompatibilityException -> when (error.problem) {
                CompatibilityProblem.WrongServer -> "По этому адресу нет сервера TiniTalk. Проверьте адрес"
                CompatibilityProblem.ServerOutdated -> "Сервер TiniTalk устарел. Обновите сервер"
                CompatibilityProblem.AppOutdated -> "Приложение TiniTalk устарело. Установите новую версию"
                CompatibilityProblem.Unavailable -> "Сервер TiniTalk временно недоступен"
            }
            is ApiException -> if (
                error.code == 401 && error.authReason == SessionReplacedReason
            ) SessionReplacedMessage else when (error.code) {
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
            authGeneration++
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
        resetToLogin()
    }

    private fun resetToLogin(errorMessage: String? = null) {
        authGeneration++
        contactNameViewModel.reset()
        historyLoadGeneration++
        contactHistoryGeneration++
        contactHistoryLogin = null
        historyVisible = false
        IncomingCallNotifier(this).clearMissedCount()
        pushRegistrationStarted = false
        loginResetKey++
        screenState = MainScreenState(
            restoring = false,
            permissions = screenState.permissions,
            errorMessage = errorMessage,
            networkAvailable = network.available,
        )
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
        when {
            contactHistoryLogin != null -> loadContactHistory(contactHistoryLogin.orEmpty(), reset = true)
            historyVisible -> showHistory()
            else -> refreshMissedCount()
        }
    }

    private fun refreshMissedCount() {
        if (!network.available || !screenState.signedIn) return
        val requestAuthGeneration = authGeneration
        val generation = historyLoadGeneration
        val badgeRefreshId = IncomingCallNotifier(this).beginMissedCountRefresh()
        Thread {
            runCatching { repository.loadCallHistory(limit = 1) }
                .onSuccess { page ->
                    if (page == null) return@onSuccess
                    runOnUiThread {
                        if (!screenState.signedIn || generation != historyLoadGeneration) return@runOnUiThread
                        applyUnreadMissedState(
                            CallUnreadState(page.unreadMissedCount, page.unreadMissed),
                            badgeRefreshId,
                        )
                    }
                }
                .onFailure {
                    if (it is ApiException && it.code == 401) {
                        showSessionErrorIfCurrent(it, requestAuthGeneration)
                    }
                }
        }.start()
    }

    private fun applyUnreadMissedState(unread: CallUnreadState, badgeRefreshId: Long) {
        val update = IncomingCallNotifier(this).updateMissedCount(
            unread.unreadMissedCount,
            badgeRefreshId,
        )
        if (update.applied) {
            screenState = screenState.withUnreadMissedState(unread, update.count)
        }
    }

    override fun onDestroy() {
        network.removeObserver(networkObserver)
        AuthSessionEvents.removeObserver(authSessionObserver)
        IncomingCallNotifier(this).removeMissedCountObserver(missedCountObserver)
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
        if (!network.available || pushRegistrationStarted || !screenState.permissions.allRequiredGranted) return
        val session = authStore.load() ?: return
        pushRegistrationStarted = true
        DeviceRegistrar.forSession(this, session).register(DeviceRegistrar.deviceId(this))
    }

    private fun updateNetworkAvailability(available: Boolean) {
        val changed = screenState.networkAvailable != available
        if (!available) {
            pushRegistrationStarted = false
            screenState = screenState.withOfflineSession(authStore.load()?.url)
            return
        }
        screenState = screenState.copy(networkAvailable = true)
        if (!changed) return
        val session = authStore.load() ?: return
        refreshPermissions()
        if (!screenState.signedIn || screenState.contacts.isEmpty()) {
            screenState = screenState.copy(restoring = true)
            restoreContacts()
            return
        }
        refreshContacts()
        when {
            contactHistoryLogin != null -> loadContactHistory(contactHistoryLogin.orEmpty(), reset = true)
            historyVisible -> showHistory()
            else -> refreshMissedCount()
        }
    }

    private fun showNoInternetMessage() {
        Toast.makeText(this, "Нет подключения к интернету", Toast.LENGTH_SHORT).show()
    }
}

private fun MainScreenState.withContactUpdates(updates: Map<String, Contact>): MainScreenState {
    if (updates.isEmpty()) return this
    val contacts = contacts
        .map { updates[it.login] ?: it }
        .sortedWith(
            compareBy<Contact, String>(String.CASE_INSENSITIVE_ORDER) { it.displayName.trim() }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.login },
        )
    return copy(
        contacts = contacts,
        history = history.map { item ->
            updates[item.peerLogin]?.let { item.copy(peerName = it.displayName) } ?: item
        },
        contactHistory = contactHistory.copy(
            items = contactHistory.items.map { item ->
                updates[item.peerLogin]?.let { item.copy(peerName = it.displayName) } ?: item
            },
        ),
    )
}
