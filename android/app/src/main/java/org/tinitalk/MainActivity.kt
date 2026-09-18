package org.tinitalk

import androidx.core.net.toUri
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
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.tinitalk.call.CallDirection
import org.tinitalk.call.CallPhase
import org.tinitalk.call.CallServiceState
import org.tinitalk.call.CallUiState
import org.tinitalk.call.CallUiStateStore
import org.tinitalk.contactPhotoAccountLifecycle
import org.tinitalk.data.AndroidKeystoreTokenCipher
import org.tinitalk.data.ApiException
import org.tinitalk.data.AccountContactPage
import org.tinitalk.data.AccountContact
import org.tinitalk.data.AccountId
import org.tinitalk.data.AccountPeerKey
import org.tinitalk.data.AccountUnreadState
import org.tinitalk.data.AuthSessionEvent
import org.tinitalk.data.AuthSessionEvents
import org.tinitalk.data.AuthRemovalReason
import org.tinitalk.data.AuthStore
import org.tinitalk.data.CallHistoryEvents
import org.tinitalk.data.Contact
import org.tinitalk.data.ContactRepository
import org.tinitalk.data.PasswordSetupRequiredException
import org.tinitalk.data.PasswordSignInRequiredException
import org.tinitalk.data.CompatibilityProblem
import org.tinitalk.data.ContactCache
import org.tinitalk.data.ContactEvents
import org.tinitalk.data.ServerCompatibilityException
import org.tinitalk.data.SessionReplacedReason
import org.tinitalk.data.httpsServerUrl
import org.tinitalk.data.normalizeServerUrl
import org.tinitalk.data.sameIdentity
import org.tinitalk.data.SharedPreferencesKeyValueStore
import org.tinitalk.network.NetworkAvailability
import org.tinitalk.network.networkAvailability
import org.tinitalk.permissions.AppPermissionsState
import org.tinitalk.push.DeviceIdentity
import org.tinitalk.push.IncomingCallNotifier
import org.tinitalk.telecom.IncomingCallController
import org.tinitalk.ui.MainScreen
import org.tinitalk.ui.MainHistoryController
import org.tinitalk.ui.HistoryEnvironment
import org.tinitalk.ui.RepositoryHistoryDataSource
import org.tinitalk.ui.MissedCallsHistoryBadgeSink
import org.tinitalk.ui.withHistory
import org.tinitalk.ui.MainScreenState
import org.tinitalk.ui.AccountPage
import org.tinitalk.ui.AccountSummary
import org.tinitalk.ui.ContactNameViewModel
import org.tinitalk.ui.ContactPhotoEditTarget
import org.tinitalk.ui.ContactPhotoEditorViewModel
import org.tinitalk.ui.ContactPhotoSource
import org.tinitalk.ui.LocalContactPhotoReader
import org.tinitalk.ui.isCurrentSessionRequest
import org.tinitalk.ui.withOfflineSession
import org.tinitalk.ui.configuredAboutServerUrl
import org.tinitalk.ui.passwordAuthErrorMessage
import org.tinitalk.ui.passwordRetryDeadline
import org.tinitalk.ui.theme.TiniTalkTheme
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CompletableFuture

private const val SessionReplacedMessage = "Вход выполнен на другом устройстве"

class MainActivity : ComponentActivity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val contactNameViewModel by viewModels<ContactNameViewModel>()
    private val contactPhotoEditorViewModel by viewModels<ContactPhotoEditorViewModel>()
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { refreshPermissions() }
    private val microphonePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { refreshPermissions() }
    private val contactPhotoGalleryLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> contactPhotoEditorViewModel.onPickerResult(uri) }
    private val contactPhotoFilesLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> contactPhotoEditorViewModel.onPickerResult(uri) }

    private lateinit var repository: ContactRepository
    private lateinit var authStore: AuthStore
    private lateinit var contactCache: ContactCache
    private lateinit var network: NetworkAvailability
    private var screenState by mutableStateOf(MainScreenState())
    private var callUiState by mutableStateOf(CallUiStateStore.snapshot())
    private var callLaunchError by mutableStateOf<CallLaunchError?>(null)
    private var launchingCall = false
    private var pinningShortcut = false
    private var shortcutToConfirm by mutableStateOf<AccountContact?>(null)
    private val missedCalls get() = (application as TinitalkApplication).missedCalls
    private val contactShortcuts get() = (application as TinitalkApplication).contactShortcuts
    private var loginResetKey by mutableIntStateOf(0)
    @Volatile
    private var mainScreenResumed = false
    private lateinit var history: MainHistoryController
    private var nextContactOpenRequestId = 0L
    private var contactOpenRequest by mutableStateOf<ContactOpenRequest?>(null)
    private var authGeneration = 0
    private var contactsGeneration = 0
    private var contactsSyncing = false
    @Volatile
    private var accountRemovalInProgress = false
    private val callUiObserver: (CallUiState) -> Unit = { state ->
        runOnUiThread { callUiState = state }
    }
    private val contactObserver: (AccountId) -> Unit = {
        mainHandler.post { reloadCachedContacts() }
    }
    private val accountMissedCountObserver: (Int) -> Unit = { count ->
        runOnUiThread {
            if (!isDestroyed) history.setMissedCount(count)
        }
    }
    private val accountCallHistoryObserver: (AccountUnreadState) -> Unit = { unread ->
        mainHandler.post {
            if (!isDestroyed && screenState.signedIn) history.onHistoryChanged(unread)
        }
    }
    private val authSessionObserver: (AuthSessionEvent) -> Unit = {
        mainHandler.post {
            if (isDestroyed) return@post
            it.accountId?.let { accountId ->
                pruneRemovedAccount(accountId, authStore.list())
            }
            if (authStore.list().isEmpty()) {
                resetToLogin(if (it.reason == AuthRemovalReason.SessionReplaced) SessionReplacedMessage else null)
            }
        }
    }
    private val accountAdditionObserver: () -> Unit = {
        mainHandler.post { consumeAccountAdditionIfResumed() }
    }
    private val networkObserver: (Boolean) -> Unit = { available ->
        mainHandler.post {
            if (!isDestroyed) updateNetworkAvailability(available)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val localStore = SharedPreferencesKeyValueStore(this)
        authStore = AuthStore(localStore, AndroidKeystoreTokenCipher())
        contactCache = ContactCache(localStore)
        repository = ContactRepository(
            this,
            authStore,
            contactCache,
            onExplicitAccountRemoved = ::cleanupContactPhotosAfterExplicitAccountRemoval,
        )
        network = networkAvailability()
        history = MainHistoryController(
            source = RepositoryHistoryDataSource(repository),
            badges = MissedCallsHistoryBadgeSink(missedCalls, authStore),
            scope = lifecycleScope,
            environment = {
                HistoryEnvironment(
                    signedIn = screenState.signedIn,
                    networkAvailable = network.available,
                    resumed = mainScreenResumed,
                    sessionGeneration = authGeneration,
                )
            },
            onSessionError = ::showSessionErrorIfCurrent,
        )
        contactPhotoEditorViewModel.configure(
            processor = (application as TinitalkApplication).contactPhotoProcessor,
            store = (application as TinitalkApplication).contactPhotoStore,
            isTargetCurrent = ::isContactPhotoTargetCurrent,
        )
        applyNavigationIntent(intent)
        screenState = screenState.copy(networkAvailable = network.available)
        setContent {
            TiniTalkTheme(darkTheme = true) {
                CompositionLocalProvider(LocalContactPhotoReader provides (application as TinitalkApplication).contactPhotoStore) {
                    val contactNameUpdate = contactNameViewModel.state
                    val pinnedContacts by contactShortcuts.pinnedContacts.collectAsState()
                    val visibleScreenState = screenState.withHistory(history.state)
                        .withContactUpdates(contactNameViewModel.updatedContacts)
                    LaunchedEffect(contactNameUpdate.authExpired) {
                        if (contactNameUpdate.authExpired) {
                            contactNameViewModel.reset()
                            if (authStore.list().isEmpty()) {
                                showError(ApiException(401, "unauthorized", contactNameUpdate.authReason))
                            }
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
                        contactOpenRequest = contactOpenRequest,
                        onContactOpenRequestHandled = ::consumeContactOpenRequest,
                        onSignIn = ::loadContacts,
                        onSetInitialPassword = ::setInitialPasswordAndLoadContacts,
                        onCancelPasswordSetup = { screenState = screenState.copy(passwordSetupRequired = false, errorMessage = null, loginRetryAtMillis = 0) },
                        onCancelAccountPasswordSetup = { screenState = screenState.copy(addAccountPasswordSetupRequired = false, addAccountErrorMessage = null, addAccountRetryAtMillis = 0) },
                        onCheckServer = repository::checkServer,
                        onCheckServerDetails = repository::checkServerDetails,
                        onCheckPasswordSet = repository::passwordSet,
                        onRequestNotifications = ::requestNotificationPermission,
                        onRequestMicrophone = ::requestMicrophonePermission,
                        onRequestFullScreenCalls = ::requestFullScreenIntentPermission,
                        onRefreshPermissions = ::refreshPermissions,
                        onCall = ::startCall,
                        onPinContact = { pinContact(it) },
                        pinnedContacts = pinnedContacts,
                        onRefreshShortcuts = contactShortcuts::refresh,
                        onRenameContact = { key, customName ->
                            if (network.available) {
                                contactNameViewModel.rename(repository, key, customName)
                            } else {
                                showNoInternetMessage()
                            }
                        },
                        onRenameHandled = contactNameViewModel::clearResult,
                        onOpenCall = { startActivity(CallActivity.ongoingIntent(this)) },
                        onContactsVisible = history::showContacts,
                        onRefreshContacts = ::refreshContacts,
                        onContactsRefreshMessageHandled = ::clearContactsRefreshMessage,
                        onHistoryVisible = history::showHistory,
                        onLoadMoreHistory = history::loadMoreHistory,
                        onContactHistoryVisible = history::showContact,
                        onContactHistoryHidden = history::hideContact,
                        onLoadMoreContactHistory = history::loadMoreContactHistory,
                        onRetryContactHistory = history::retryContactHistory,
                        contactPhotoEditorState = contactPhotoEditorViewModel.state,
                        onContactPhotoTargetVisible = contactPhotoEditorViewModel::onTargetVisible,
                        onContactPhotoTargetHidden = contactPhotoEditorViewModel::onTargetHidden,
                        onChooseContactPhoto = ::chooseContactPhoto,
                        onRemoveContactPhoto = { target -> contactPhotoEditorViewModel.remove(target) },
                        onCancelContactPhotoCrop = contactPhotoEditorViewModel::cancelCrop,
                        onConfirmContactPhotoCrop = { crop -> contactPhotoEditorViewModel.save(crop) },
                        onContactPhotoMessageShown = contactPhotoEditorViewModel::onMessageShown,
                        onOpenProfile = { screenState = screenState.copy(accountPage = AccountPage.Profile) },
                        onCloseProfile = { screenState = screenState.copy(accountPage = AccountPage.Main) },
                        onOpenAddAccount = {
                            screenState = screenState.copy(accountPage = AccountPage.AddAccount, addAccountErrorMessage = null, signInRecovery = null)
                        },
                        onCloseAddAccount = {
                            if (!screenState.addingAccount) screenState = screenState.copy(accountPage = AccountPage.Profile, signInRecovery = null)
                        },
                        onAddAccount = ::addAccount,
                        onSetInitialPasswordForAccount = ::setInitialPasswordAndAddAccount,
                        onRemoveAccount = ::removeAccount,
                        onChangePassword = ::changePassword,
                        onCheckAddAccountServer = repository::checkAddAccountServer,
                        onOpenAddContact = {
                            screenState = screenState.copy(
                                accountPage = AccountPage.AddContact,
                                addContactErrorMessage = null,
                            )
                        },
                        onCloseAddContact = {
                            if (!screenState.addingContact) {
                                screenState = screenState.copy(
                                    accountPage = AccountPage.Main,
                                    addContactErrorMessage = null,
                                )
                            }
                        },
                        onAddContactInputChanged = {
                            if (screenState.addContactErrorMessage != null) {
                                screenState = screenState.copy(addContactErrorMessage = null)
                            }
                        },
                        onAddContact = ::addContact,
                        onRemoveContact = ::removeContact,
                        onRemoveContactDismissed = {
                            if (screenState.removingContact == null) {
                                screenState = screenState.copy(
                                    removeContactErrorFor = null,
                                    removeContactErrorMessage = null,
                                )
                            }
                        },
                    )
                    callLaunchError?.let { error ->
                        CallLaunchErrorDialog(
                            error,
                            onDismiss = { callLaunchError = null },
                            onRequestMicrophone = {
                                callLaunchError = null
                                requestMicrophonePermission()
                            },
                        )
                    }
                    shortcutToConfirm?.let { contact ->
                        AlertDialog(
                            onDismissRequest = { shortcutToConfirm = null },
                            title = { Text("Ярлык уже добавлен") },
                            text = { Text("Ярлык контакта «${contact.displayName}» уже добавлен на главный экран. Добавить ещё один?") },
                            confirmButton = {
                                TextButton(onClick = {
                                    shortcutToConfirm = null
                                    pinContact(contact, allowDuplicate = true)
                                }) { Text("Добавить ещё") }
                            },
                            dismissButton = {
                                TextButton(onClick = { shortcutToConfirm = null }) { Text("Отмена") }
                            },
                        )
                    }
                }
            }
        }
        CallUiStateStore.observe(callUiObserver)
        missedCalls.observeCount(accountMissedCountObserver)
        CallHistoryEvents.observeAccount(accountCallHistoryObserver)
        ContactEvents.observe(contactObserver)
        AuthSessionEvents.observe(authSessionObserver)
        accountAdditionHandoff.observe(accountAdditionObserver)
        network.observe(networkObserver)
        refreshPermissions()
        restoreContacts()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyNavigationIntent(intent)
    }

    private fun applyNavigationIntent(intent: Intent?) {
        val peer = contactPeerFromIntent(intent)
        if (peer == null) {
            contactOpenRequest = null
            return
        }
        contactOpenRequest = ContactOpenRequest(++nextContactOpenRequestId, peer)
        if (screenState.accountPage != AccountPage.Main) {
            screenState = screenState.copy(accountPage = AccountPage.Main)
        }
    }

    private fun consumeContactOpenRequest(request: ContactOpenRequest) {
        if (contactOpenRequest != request) return
        contactOpenRequest = null
        setIntent(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN))
    }

    private fun chooseContactPhoto(target: ContactPhotoEditTarget, source: ContactPhotoSource) {
        if (!contactPhotoEditorViewModel.beginPicking(target, source)) return
        when (source) {
            ContactPhotoSource.Gallery -> contactPhotoGalleryLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
            ContactPhotoSource.Files -> contactPhotoFilesLauncher.launch(arrayOf("image/*"))
        }
    }

    private fun isContactPhotoTargetCurrent(target: ContactPhotoEditTarget): Boolean {
        val account = authStore.get(target.accountId) ?: return false
        if (normalizeServerUrl(account.session.url) != target.address.serverUrl) return false
        return screenState.accountContacts.any { contact ->
            contact.accountId == target.accountId &&
                contact.login == target.address.login &&
                contact.address == target.address
        }
    }

    private fun restoreContacts() {
        val accounts = repository.accounts()
        if (accounts.isEmpty()) {
            screenState = MainScreenState(
                restoring = false,
                permissions = screenState.permissions,
                networkAvailable = network.available,
            )
            return
        }
        showContacts(accounts.map(contactCache::load))
        if (!network.available) {
            screenState = screenState.copy(networkAvailable = false)
            return
        }
        refreshContacts(showProgress = false)
    }

    private fun loadContacts(url: String, login: String, token: String) {
        if (!network.available) {
            showNoInternetMessage()
            return
        }
        contactNameViewModel.reset()
        authGeneration++
        val requestAuthGeneration = authGeneration
        screenState = screenState.copy(signingIn = true, errorMessage = null, loginRetryAtMillis = 0)
        val serverUrl = checkNotNull(httpsServerUrl(url))
        val deviceId = DeviceIdentity.id(this)
        Thread {
            runCatching { repository.signIn(url, login, token, deviceId) }
                .onSuccess { page ->
                    completeInitialSignIn(serverUrl, login, page, requestAuthGeneration)
                }
                .onFailure { error ->
                    if (error is PasswordSetupRequiredException) {
                        runOnUiThread {
                            if (isCurrentSessionRequest(requestAuthGeneration, authGeneration)) {
                                screenState = screenState.copy(
                                    signingIn = false,
                                    passwordSetupRequired = true,
                                    errorMessage = null,
                                )
                            }
                        }
                    } else {
                        if (!showSavedLoginAfterFailure(serverUrl, login, requestAuthGeneration)) {
                            showSessionErrorIfCurrent(error, requestAuthGeneration)
                        }
                    }
                }
        }.start()
    }

    private fun setInitialPasswordAndLoadContacts(
        url: String,
        login: String,
        temporaryPassword: String,
        newPassword: String,
    ) {
        if (!network.available || screenState.signingIn) return
        authGeneration++
        val requestAuthGeneration = authGeneration
        screenState = screenState.copy(signingIn = true, errorMessage = null, loginRetryAtMillis = 0)
        val serverUrl = checkNotNull(httpsServerUrl(url))
        val deviceId = DeviceIdentity.id(this)
        Thread {
            runCatching {
                repository.setInitialPassword(url, login, temporaryPassword, newPassword, deviceId)
            }.onSuccess { page ->
                completeInitialSignIn(serverUrl, login, page, requestAuthGeneration)
            }.onFailure { error ->
                if (showSavedLoginAfterFailure(serverUrl, login, requestAuthGeneration)) return@onFailure
                if (error is ApiException) {
                    showSessionErrorIfCurrent(error, requestAuthGeneration)
                } else {
                    runOnUiThread {
                        if (isCurrentSessionRequest(requestAuthGeneration, authGeneration)) {
                            screenState = screenState.copy(
                                signingIn = false,
                                passwordSetupRequired = false,
                                errorMessage = passwordSetupRecoveryMessage(),
                            )
                        }
                    }
                }
            }
        }.start()
    }

    private fun showSavedLoginAfterFailure(serverUrl: String, login: String, generation: Int): Boolean {
        val saved = repository.accounts().any {
            it.session.url == serverUrl && it.session.login == login.trim() &&
                org.tinitalk.data.PASSWORD_AUTH_FEATURE in it.session.features
        }
        if (!saved) return false
        runOnUiThread {
            if (!isCurrentSessionRequest(generation, authGeneration)) return@runOnUiThread
            screenState = screenState.copy(passwordSetupRequired = false)
            restoreContacts()
            Toast.makeText(this, "Вход сохранён. Восстанавливаем подключение", Toast.LENGTH_LONG).show()
        }
        return true
    }

    private fun completeInitialSignIn(
        serverUrl: String,
        login: String,
        page: org.tinitalk.data.ContactPage,
        requestAuthGeneration: Int,
    ) {
        contactPhotoAccountLifecycle(this).activateServer(serverUrl)
        runOnUiThread {
            if (!isCurrentSessionRequest(requestAuthGeneration, authGeneration)) return@runOnUiThread
            val account = repository.accounts().singleOrNull {
                it.session.url == serverUrl && it.session.login == login.trim()
            }
            if (account != null) {
                screenState = screenState.copy(passwordSetupRequired = false, signInRecovery = null)
                showContacts(
                    listOf(
                        AccountContactPage(
                            account.id,
                            page.items.map { contact ->
                                org.tinitalk.data.AccountContact(account.id, serverUrl, contact)
                            },
                        ),
                    ),
                )
            }
        }
    }

    private fun startCall(accountContact: AccountContact) {
        if (launchingCall) return
        launchingCall = true
        lifecycleScope.launch {
            try {
                callLaunchError = launchContactCall(accountContact.peerKey)
            } finally {
                launchingCall = false
            }
        }
    }

    private fun pinContact(contact: AccountContact, allowDuplicate: Boolean = false) {
        if (pinningShortcut) return
        pinningShortcut = true
        lifecycleScope.launch {
            try {
                val supported = withContext(Dispatchers.IO) { runCatching { contactShortcuts.isSupported() } }
                if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@launch
                if (supported.getOrNull() == false) {
                    Toast.makeText(this@MainActivity, "Этот главный экран не поддерживает добавление ярлыков", Toast.LENGTH_LONG).show()
                    return@launch
                }
                val prepared = withContext(Dispatchers.IO) {
                    runCatching {
                        supported.getOrThrow()
                        resolveContactCallTarget(authStore, contactCache, contact.peerKey)?.contact?.let(contactShortcuts::preparePin)
                    }
                }
                if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@launch
                val shortcut = prepared.getOrNull()
                if (shortcut?.alreadyPinned == true && !allowDuplicate) {
                    shortcutToConfirm = contact
                    return@launch
                }
                val requested = shortcut != null && withContext(Dispatchers.IO) {
                    runCatching { contactShortcuts.requestPin(shortcut.info) }.getOrDefault(false)
                }
                if (!requested && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    Toast.makeText(this@MainActivity, "Не удалось добавить ярлык. Проверьте контакт и попробуйте ещё раз.", Toast.LENGTH_LONG).show()
                }
            } finally {
                pinningShortcut = false
            }
        }
    }

    private fun showContacts(pages: List<AccountContactPage>) {
        runOnUiThread {
            authGeneration++
            contactsGeneration++
            history.reset(keepHistorySelection = true)
            val accounts = repository.accounts()
            val accountOrder = accounts.map { it.id }
            screenState = screenState.copy(
                restoring = false,
                signingIn = false,
                signedIn = true,
                serverUrl = accounts.aboutServerUrl(),
                accounts = accounts.toAccountSummaries(),
                accountContacts = org.tinitalk.ui.mergeAccountContacts(
                    accountOrder,
                    pages.associate { it.accountId to it.items },
                ),
                contactsRefreshing = false,
                contactsRefreshErrorMessage = null,
                addingContact = false,
                addContactErrorMessage = null,
                removingContact = null,
                removeContactErrorFor = null,
                removeContactErrorMessage = null,
                errorMessage = null,
            )
            missedCalls.syncAccounts(accountOrder)
            refreshPermissions()
            history.refreshMissedCount()
        }
    }

    private fun refreshContacts(showProgress: Boolean = true) {
        if (!network.available || !screenState.signedIn || contactsSyncing) return
        val requestAuthGeneration = authGeneration
        val requestContactsGeneration = contactsGeneration
        val accounts = repository.accounts()
        if (accounts.isEmpty()) return
        contactsSyncing = true
        if (showProgress) {
            screenState = screenState.copy(
                contactsRefreshing = true,
                contactsRefreshErrorMessage = null,
            )
        }
        val requests = accounts.map { account ->
            CompletableFuture.supplyAsync {
                runCatching { repository.refreshContacts(account.id, DeviceIdentity.id(this)) }.getOrNull()
            }
        }
        CompletableFuture.allOf(*requests.toTypedArray()).whenComplete { _, _ ->
            val pages = requests.mapNotNull { request -> runCatching { request.getNow(null) }.getOrNull() }
            runOnUiThread {
                contactsSyncing = false
                if (!screenState.signedIn || !isCurrentSessionRequest(requestAuthGeneration, authGeneration)) {
                    return@runOnUiThread
                }
                if (requestContactsGeneration != contactsGeneration) {
                    screenState = screenState.copy(contactsRefreshing = false)
                    return@runOnUiThread
                }
                val activeAccounts = repository.accounts().filter { current ->
                    accounts.any { it.id == current.id && it.session.token == current.session.token }
                }
                val activeIds = activeAccounts.map { it.id }.toSet()
                val updated = pages.any { it.accountId in activeIds }
                screenState = screenState.copy(
                    accountContacts = org.tinitalk.ui.mergeAccountContacts(
                        activeAccounts.map { it.id },
                        activeAccounts.associate { it.id to contactCache.load(it).items },
                    ),
                    contactsRefreshing = false,
                    contactsRefreshErrorMessage = if (showProgress && !updated) {
                        "Не удалось обновить контакты"
                    } else {
                        null
                    },
                )
            }
        }
    }

    private fun clearContactsRefreshMessage() {
        screenState = screenState.copy(contactsRefreshErrorMessage = null)
    }

    private fun reloadCachedContacts() {
        if (isDestroyed || !screenState.signedIn) return
        val accounts = repository.accounts()
        screenState = screenState.copy(
            accountContacts = org.tinitalk.ui.mergeAccountContacts(
                accounts.map { it.id },
                accounts.associate { it.id to contactCache.load(it).items },
            ),
        )
    }

    private fun showSessionErrorIfCurrent(
        error: Throwable,
        requestAuthGeneration: Int,
    ) {
        runOnUiThread {
            if (!isCurrentSessionRequest(requestAuthGeneration, authGeneration)) {
                return@runOnUiThread
            }
            if (error is ApiException && error.code == 401 && error.errorCode == null && authStore.list().isNotEmpty()) return@runOnUiThread
            showError(error)
        }
    }

    private fun showError(error: Throwable) {
        val message = when (error) {
            is ServerCompatibilityException -> when (error.problem) {
                CompatibilityProblem.WrongServer -> "По этому адресу нет сервера TiniTalk. Проверьте адрес"
                CompatibilityProblem.ServerOutdated -> "Сервер несовместим с этой версией приложения"
                CompatibilityProblem.AppOutdated -> "Приложение TiniTalk устарело. Установите новую версию"
                CompatibilityProblem.Unavailable -> "Сервер TiniTalk временно недоступен"
            }
            is ApiException -> if (error.errorCode != null) {
                passwordAuthErrorMessage(error)
            } else if (
                error.code == 401 && error.authReason == SessionReplacedReason
            ) SessionReplacedMessage else when (error.code) {
                401 -> "Неверный логин или пароль"
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
            history.invalidateLoads()
            screenState = screenState.copy(
                restoring = false,
                signingIn = false,
                signedIn = false,
                errorMessage = message,
                loginRetryAtMillis = passwordRetryDeadline(error),
            )
        }
    }

    private fun addAccount(url: String, login: String, token: String) {
        if (!network.available || screenState.addingAccount || accountRemovalInProgress) return
        screenState = screenState.copy(addingAccount = true, addAccountErrorMessage = null, addAccountRetryAtMillis = 0)
        val deviceId = DeviceIdentity.id(this)
        Thread {
            runCatching { repository.addAccount(url, login, token, deviceId) }
                .onSuccess { added ->
                    contactPhotoAccountLifecycle(this).activateServer(added.account.session.url)
                    accountAdditionHandoff.publish(
                        AccountAdditionOutcome.Added(
                            accountId = added.account.id,
                            sessionId = added.account.session.sessionId,
                            configId = added.account.session.configId,
                            contacts = added.contacts,
                        ),
                    )
                }.onFailure { error ->
                    if (error is PasswordSetupRequiredException) {
                        runOnUiThread {
                            screenState = screenState.copy(
                                addingAccount = false,
                                addAccountPasswordSetupRequired = true,
                                addAccountErrorMessage = null,
                            )
                        }
                    } else {
                        runOnUiThread { screenState = screenState.copy(addAccountRetryAtMillis = passwordRetryDeadline(error)) }
                        accountAdditionHandoff.publish(AccountAdditionOutcome.Failed(userErrorMessage(error)))
                    }
                }
        }.start()
    }

    private fun setInitialPasswordAndAddAccount(
        url: String,
        login: String,
        temporaryPassword: String,
        newPassword: String,
    ) {
        if (!network.available || screenState.addingAccount || accountRemovalInProgress) return
        screenState = screenState.copy(addingAccount = true, addAccountErrorMessage = null, addAccountRetryAtMillis = 0)
        val deviceId = DeviceIdentity.id(this)
        Thread {
            runCatching {
                repository.setInitialPasswordAndAddAccount(
                    url,
                    login,
                    temporaryPassword,
                    newPassword,
                    deviceId,
                )
            }.onSuccess { added ->
                contactPhotoAccountLifecycle(this).activateServer(added.account.session.url)
                accountAdditionHandoff.publish(
                    AccountAdditionOutcome.Added(
                        accountId = added.account.id,
                        sessionId = added.account.session.sessionId,
                        configId = added.account.session.configId,
                        contacts = added.contacts,
                    ),
                )
            }.onFailure { error ->
                val message = if (error is ApiException) userErrorMessage(error) else passwordSetupRecoveryMessage()
                runOnUiThread {
                    screenState = screenState.copy(
                        addAccountRetryAtMillis = passwordRetryDeadline(error),
                        addAccountPasswordSetupRequired = error is ApiException,
                    )
                }
                accountAdditionHandoff.publish(AccountAdditionOutcome.Failed(message))
            }
        }.start()
    }

    private fun addContact(accountId: AccountId, login: String, name: String) {
        if (!network.available || screenState.addingContact || screenState.removingContact != null) return
        val account = repository.accounts().firstOrNull { it.id == accountId } ?: return
        val key = AccountPeerKey(accountId, login.trim())
        if (account.session.login == key.login) {
            screenState = screenState.copy(addContactErrorMessage = "Нельзя добавить свой аккаунт в контакты")
            return
        }
        if (screenState.accountContacts.any { it.peerKey == key }) {
            screenState = screenState.copy(addContactErrorMessage = "Этот контакт уже есть в вашей телефонной книге")
            return
        }
        contactsGeneration++
        screenState = screenState.copy(addingContact = true, addContactErrorMessage = null)
        Thread {
            runCatching { repository.addContact(accountId, key.login, name) }
                .onSuccess { added ->
                    runOnUiThread {
                        if (added == null || repository.accounts().none { it.id == accountId }) {
                            screenState = screenState.copy(addingContact = false)
                            return@runOnUiThread
                        }
                        contactNameViewModel.forget(key)
                        screenState = screenState.copy(
                            accountPage = AccountPage.Main,
                            addingContact = false,
                            addContactErrorMessage = null,
                        )
                        reloadCachedContacts()
                    }
                }
                .onFailure { error ->
                    runOnUiThread {
                        val accountStillExists = repository.accounts().any { it.id == accountId }
                        screenState = screenState.copy(
                            addingContact = false,
                            addContactErrorMessage = contactAddError(error, key.login, account.session.url)
                                .takeIf { accountStillExists },
                        )
                    }
                }
        }.start()
    }

    private fun removeContact(contact: AccountContact) {
        if (!network.available || screenState.addingContact || screenState.removingContact != null) return
        val key = contact.peerKey
        contactsGeneration++
        screenState = screenState.copy(
            removingContact = key,
            removeContactErrorFor = null,
            removeContactErrorMessage = null,
        )
        Thread {
            runCatching {
                check(repository.removeContact(key.accountId, key.login)) { "session ended" }
                (application as TinitalkApplication).contactPhotoStore.remove(contact.address)
            }.onSuccess {
                runOnUiThread {
                    org.tinitalk.data.FavoriteContactsStore(this).setFavorite(key, false)
                    contactPhotoEditorViewModel.state.target
                        ?.takeIf { it.accountId == key.accountId && it.address == contact.address }
                        ?.let(contactPhotoEditorViewModel::onTargetHidden)
                    contactNameViewModel.forget(key)
                    history.removeContact(key)
                    screenState = screenState.copy(
                        removingContact = null,
                        removeContactErrorFor = null,
                        removeContactErrorMessage = null,
                        accountContacts = screenState.accountContacts.filterNot { it.peerKey == key },
                    )
                }
            }.onFailure { error ->
                runOnUiThread {
                    val accountStillExists = repository.accounts().any { it.id == key.accountId }
                    screenState = screenState.copy(
                        removingContact = null,
                        removeContactErrorFor = key.takeIf { accountStillExists },
                        removeContactErrorMessage = contactRemoveError(error).takeIf { accountStillExists },
                    )
                }
            }
        }.start()
    }

    private fun consumeAccountAdditionIfResumed() {
        if (isDestroyed || !mainScreenResumed) return
        val outcomes = accountAdditionHandoff.drain()
        val restoreAfterSuccess = screenState.restoring && outcomes.any { it is AccountAdditionOutcome.Added }
        outcomes.forEach(::applyAccountAdditionOutcome)
        if (restoreAfterSuccess && repository.accounts().isNotEmpty()) {
            if (screenState.accountPage != AccountPage.AddAccount) {
                screenState = screenState.copy(restoring = true)
            }
            restoreContacts()
        }
    }

    private fun applyAccountAdditionOutcome(outcome: AccountAdditionOutcome) {
        when (outcome) {
            is AccountAdditionOutcome.Added -> applyAccountAddition(outcome)
            is AccountAdditionOutcome.Failed -> {
                val accounts = repository.accounts()
                val stagedAddition = accounts.any { account ->
                    screenState.accounts.none { it.id == account.id }
                }
                screenState = screenState.copy(
                    restoring = false,
                    signingIn = false,
                    signedIn = accounts.isNotEmpty(),
                    addingAccount = false,
                    accountPage = if (stagedAddition) AccountPage.Main else AccountPage.AddAccount,
                    addAccountPasswordSetupRequired = screenState.addAccountPasswordSetupRequired && !stagedAddition,
                    addAccountErrorMessage = outcome.message.takeUnless { stagedAddition },
                    serverUrl = accounts.aboutServerUrl(),
                    accounts = accounts.toAccountSummaries(),
                )
                if (stagedAddition) {
                    refreshContacts(showProgress = false)
                    Toast.makeText(this, "Вход сохранён. Восстанавливаем подключение", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun applyAccountAddition(completion: AccountAdditionOutcome.Added) {
        val accounts = repository.accounts()
        val current = accounts.firstOrNull {
            it.id == completion.accountId &&
                it.session.sessionId == completion.sessionId &&
                it.session.configId == completion.configId
        }
        authGeneration++
        clearInFlightPresentationAfterAccountAdd()
        if (current == null || completion.contacts.accountId != completion.accountId) {
            screenState = screenState.copy(
                restoring = true,
                addingAccount = false,
                addAccountErrorMessage = null,
                accounts = accounts.toAccountSummaries(),
                serverUrl = accounts.aboutServerUrl(),
            )
            if (accounts.isEmpty()) resetToLogin() else restoreContacts()
            return
        }
        screenState = screenState.copy(
            signedIn = true,
            accountPage = AccountPage.Main,
            addingAccount = false,
            addAccountPasswordSetupRequired = false,
            addAccountErrorMessage = null,
            signInRecovery = null,
            serverUrl = accounts.aboutServerUrl(),
            accounts = accounts.toAccountSummaries(),
            accountContacts = org.tinitalk.ui.mergeAccountContacts(
                accounts.map { it.id },
                accounts.associate { it.id to contactCache.load(it).items },
            ),
        )
        missedCalls.syncAccounts(accounts.map { it.id })
        history.refreshMissedCount()
    }

    private fun removeAccount(accountId: AccountId) {
        if (accountRemovalInProgress || screenState.addingAccount || screenState.addingContact ||
            screenState.removingContact != null
        ) return
        accountRemovalInProgress = true
        Thread {
            val removal = runCatching { repository.removeAccount(accountId) }
            val removed = removal.getOrDefault(false)
            val remaining = repository.accounts()
            runOnUiThread {
                accountRemovalInProgress = false
                removal.exceptionOrNull()?.let { error ->
                    val message = when (error) {
                        is UnknownHostException, is SocketTimeoutException ->
                            "Не удалось отозвать вход на сервере. Аккаунт остался на устройстве"
                        else -> "Не удалось выйти: сервер не подтвердил отзыв входа"
                    }
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                }
                if (!removed) {
                    screenState = screenState.copy(accounts = remaining.toAccountSummaries())
                    return@runOnUiThread
                }
                pruneRemovedAccount(accountId, remaining)
                if (remaining.isEmpty()) {
                    resetToLogin()
                }
            }
        }.start()
    }

    private fun changePassword(accountId: AccountId, currentPassword: String, newPassword: String) {
        if (!network.available) {
            screenState = screenState.copy(passwordChangeErrorMessage = "Нет подключения к интернету")
            return
        }
        if (screenState.passwordChanging || accountRemovalInProgress) return
        if (callUiState.phase != CallPhase.Idle && callUiState.phase != CallPhase.Ended) {
            screenState = screenState.copy(passwordChangeErrorMessage = "Сначала завершите звонок")
            return
        }
        val previous = repository.accounts().firstOrNull { it.id == accountId } ?: return
        screenState = screenState.copy(passwordChanging = true, passwordChangeErrorMessage = null, passwordRetryAtMillis = 0)
        val previousToken = previous.session.token
        val deviceId = DeviceIdentity.id(this)
        Thread {
            runCatching { repository.changePassword(accountId, currentPassword, newPassword, deviceId) }
                .onSuccess {
                    runOnUiThread {
                        screenState = screenState.copy(
                            passwordChanging = false,
                            passwordChangeErrorMessage = null,
                            passwordChangeCompletionKey = screenState.passwordChangeCompletionKey + 1,
                            accounts = repository.accounts().toAccountSummaries(),
                        )
                        Toast.makeText(this, "Пароль сохранён", Toast.LENGTH_SHORT).show()
                    }
                }
                .onFailure { error ->
                    runOnUiThread {
                        val saved = repository.accounts().firstOrNull { it.id == accountId }
                        if (saved != null && saved.session.token != previousToken) {
                            screenState = screenState.copy(
                                passwordChanging = false,
                                passwordChangeErrorMessage = null,
                                passwordChangeCompletionKey = screenState.passwordChangeCompletionKey + 1,
                                accounts = repository.accounts().toAccountSummaries(),
                            )
                            refreshContacts(showProgress = false)
                            Toast.makeText(this, "Пароль сохранён. Восстанавливаем подключение", Toast.LENGTH_LONG).show()
                            return@runOnUiThread
                        }
                        if (error is PasswordSignInRequiredException) {
                            showPasswordSignInRecovery(previous)
                            return@runOnUiThread
                        }
                        screenState = screenState.copy(
                            passwordChanging = false,
                            passwordChangeErrorMessage = userErrorMessage(error),
                            passwordRetryAtMillis = passwordRetryDeadline(error),
                        )
                    }
                }
        }.start()
    }

    private fun showPasswordSignInRecovery(account: org.tinitalk.data.AccountRecord) {
        val remaining = repository.accounts()
        val message = "Не удалось получить ответ сервера. Войдите с новым паролем. Если он не подходит — используйте прежние данные для входа."
        pruneRemovedAccount(account.id, remaining, clearFavorites = false)
        if (remaining.isEmpty()) {
            resetToLogin(message)
        } else {
            authGeneration++
            history.invalidateLoads()
            loginResetKey++
            screenState = screenState.copy(
                restoring = false,
                signingIn = false,
                signedIn = true,
                accountPage = AccountPage.AddAccount,
                addingAccount = false,
                addAccountPasswordSetupRequired = false,
                addAccountRetryAtMillis = 0,
                addAccountErrorMessage = message,
                passwordChanging = false,
                passwordChangeErrorMessage = null,
                passwordRetryAtMillis = 0,
            )
        }
        screenState = screenState.copy(signInRecovery = listOf(account).toAccountSummaries().single())
    }

    private fun cleanupContactPhotosAfterExplicitAccountRemoval(accountId: AccountId, session: org.tinitalk.data.Session) {
        val invalidated = CompletableFuture<Unit>()
        mainHandler.post {
            contactPhotoEditorViewModel.state.target
                ?.takeIf { target -> target.accountId == accountId }
                ?.let(contactPhotoEditorViewModel::onTargetHidden)
            invalidated.complete(Unit)
        }
        invalidated.join()
        contactPhotoAccountLifecycle(this).removeServerAfterExplicitLogout(session.url)
    }

    private fun pruneRemovedAccount(
        accountId: AccountId,
        remaining: List<org.tinitalk.data.AccountRecord>,
        clearFavorites: Boolean = true,
    ) {
        if (clearFavorites) org.tinitalk.data.FavoriteContactsStore(this).removeAccount(accountId)
        history.removeAccount(accountId)
        screenState = screenState.copy(
            accountContacts = screenState.accountContacts.filterNot { it.accountId == accountId },
            removingContact = screenState.removingContact?.takeUnless { it.accountId == accountId },
            removeContactErrorFor = screenState.removeContactErrorFor?.takeUnless { it.accountId == accountId },
            removeContactErrorMessage = screenState.removeContactErrorMessage.takeUnless {
                screenState.removeContactErrorFor?.accountId == accountId
            },
            serverUrl = remaining.aboutServerUrl(),
            accounts = remaining.toAccountSummaries(),
        )
        missedCalls.syncAccounts(remaining.map { it.id })
    }

    private fun resetToLogin(errorMessage: String? = null) {
        authGeneration++
        contactNameViewModel.reset()
        history.reset()
        loginResetKey++
        screenState = MainScreenState(
            restoring = false,
            permissions = screenState.permissions,
            errorMessage = errorMessage,
            networkAvailable = network.available,
        )
    }

    private fun showOfflineAccounts() {
        history.onOffline()
        val accounts = repository.accounts()
        screenState = screenState.withOfflineSession(
            serverUrl = accounts.aboutServerUrl(),
            signedIn = accounts.isNotEmpty(),
        ).copy(accounts = accounts.toAccountSummaries())
    }

    private fun clearInFlightPresentationAfterAccountAdd() {
        history.invalidateLoads()
        screenState = screenState.copy(
            contactsRefreshing = false,
            contactsRefreshErrorMessage = null,
        )
    }

    override fun onResume() {
        super.onResume()
        contactShortcuts.refresh()
        mainScreenResumed = true
        cleanupStaleIncomingPresentation()
        consumeAccountAdditionIfResumed()
        refreshPermissions()
        reloadCachedContacts()
        refreshContacts(showProgress = false)
        history.refreshVisible()
    }

    override fun onPause() {
        mainScreenResumed = false
        super.onPause()
    }

    private fun cleanupStaleIncomingPresentation() {
        val pendingIncoming = IncomingCallController().load(this)
        val serviceCall = CallServiceState.snapshot()
        if (
            pendingIncoming == null &&
            (serviceCall.phase == CallPhase.Idle || serviceCall.phase == CallPhase.Ended)
        ) {
            IncomingCallNotifier(this).cancel()
            val uiCall = CallUiStateStore.snapshot()
            if (uiCall.direction == CallDirection.Incoming && uiCall.phase == CallPhase.Ringing) {
                uiCall.callKey?.let(CallUiStateStore::reset) ?: CallUiStateStore.reset()
            }
        }
    }

    override fun onDestroy() {
        network.removeObserver(networkObserver)
        accountAdditionHandoff.removeObserver(accountAdditionObserver)
        AuthSessionEvents.removeObserver(authSessionObserver)
        CallHistoryEvents.removeAccountObserver(accountCallHistoryObserver)
        ContactEvents.removeObserver(contactObserver)
        missedCalls.removeCountObserver(accountMissedCountObserver)
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
                    .setData("package:$packageName".toUri()),
            )
        }
    }

    private fun updateNetworkAvailability(available: Boolean) {
        val changed = screenState.networkAvailable != available
        if (!available) {
            showOfflineAccounts()
            return
        }
        screenState = screenState.copy(networkAvailable = true)
        if (!changed) return
        if (repository.accounts().isEmpty()) return
        refreshPermissions()
        if (!screenState.signedIn || screenState.accountContacts.isEmpty()) {
            screenState = screenState.copy(restoring = true)
            restoreContacts()
            return
        }
        refreshContacts(showProgress = false)
        history.refreshVisible()
    }

    private fun showNoInternetMessage() {
        Toast.makeText(this, "Нет подключения к интернету", Toast.LENGTH_SHORT).show()
    }
}

private fun List<org.tinitalk.data.AccountRecord>.toAccountSummaries(): List<AccountSummary> = map { account ->
    AccountSummary(account.id, account.session.url, account.session.login, account.displayName, account.session.passwordSet)
}

private fun List<org.tinitalk.data.AccountRecord>.aboutServerUrl(): String =
    configuredAboutServerUrl(map { it.session.url })

private fun userErrorMessage(error: Throwable): String = when (error) {
    is org.tinitalk.data.DuplicateAccountException -> "Аккаунт с этого сервера уже добавлен"
    is ServerCompatibilityException -> when (error.problem) {
        CompatibilityProblem.WrongServer -> "По этому адресу нет сервера TiniTalk. Проверьте адрес"
        CompatibilityProblem.ServerOutdated -> error.serverUrl
            ?.takeIf(String::isNotBlank)
            ?.let { server ->
                "Сервер $server пока не поддерживает несколько аккаунтов. Добавить ещё один аккаунт сейчас нельзя."
            }
            ?: "Сервер несовместим с этой версией приложения"
        CompatibilityProblem.AppOutdated -> "Приложение TiniTalk устарело. Установите новую версию"
        CompatibilityProblem.Unavailable -> "Сервер TiniTalk временно недоступен"
    }
    is ApiException -> if (error.errorCode != null) {
        passwordAuthErrorMessage(error)
    } else if (error.code == 401) {
        "Неверный логин или пароль"
    } else {
        "Сервер вернул ошибку ${error.code}"
    }
    else -> "Не удалось подключиться к серверу"
}

private fun passwordSetupRecoveryMessage(): String =
    "Не удалось получить ответ сервера. Вернитесь ко входу и попробуйте новый пароль."

private fun contactAddError(error: Throwable, login: String, serverUrl: String): String = when (error) {
    is ServerCompatibilityException -> "Сервер ${serverUrl.removePrefix("https://")} необходимо обновить, чтобы добавлять контакты"
    is ApiException -> when (error.code) {
        400 -> "Проверьте логин и имя контакта"
        404 -> "Пользователь «$login» не найден на сервере ${serverUrl.removePrefix("https://")}. Проверьте логин"
        409 -> "Этот контакт уже есть в вашей телефонной книге"
        else -> "Сервер вернул ошибку ${error.code}"
    }
    is UnknownHostException -> "Сервер не найден. Проверьте подключение к интернету"
    is SocketTimeoutException -> "Сервер не отвечает. Попробуйте ещё раз"
    else -> "Не удалось добавить контакт. Проверьте соединение"
}

private fun contactRemoveError(error: Throwable): String = when (error) {
    is ServerCompatibilityException -> "Сервер необходимо обновить, чтобы удалять контакты"
    is SocketTimeoutException -> "Сервер не отвечает. Попробуйте ещё раз"
    is UnknownHostException -> "Нет связи с сервером. Проверьте интернет"
    else -> "Не удалось удалить контакт. Попробуйте ещё раз"
}

private fun MainScreenState.withContactUpdates(updates: Map<org.tinitalk.data.AccountPeerKey, Contact>): MainScreenState {
    if (updates.isEmpty()) return this
    val sortedAccountContacts = org.tinitalk.ui.sortAccountContacts(
        accountContacts.map { accountContact ->
            updates[accountContact.peerKey]?.let {
                accountContact.copy(contact = accountContact.contact.copy(
                    displayName = it.displayName,
                    customName = it.customName,
                ))
            } ?: accountContact
        },
    )
    val updatedHistory = accountHistory.map { history ->
        updates[org.tinitalk.data.AccountPeerKey(history.accountId, history.peerLogin)]
            ?.let { contact -> history.copy(item = history.item.copy(peerName = contact.displayName)) } ?: history
    }
    val selectedHistory = contactHistory.let { value ->
        val accountId = value.accountId
        if (accountId == null) value else value.copy(items = value.items.map { item ->
            updates[org.tinitalk.data.AccountPeerKey(accountId, item.peerLogin)]
                ?.let { item.copy(peerName = it.displayName) } ?: item
        })
    }
    return copy(
        accountContacts = sortedAccountContacts,
        accountHistory = updatedHistory,
        contactHistory = selectedHistory,
    )
}
