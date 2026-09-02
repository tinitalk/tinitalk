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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import org.tinitalk.call.CallPhase
import org.tinitalk.call.CallSessionBinding
import org.tinitalk.call.CallServiceState
import org.tinitalk.call.CallUiState
import org.tinitalk.call.CallUiStateStore
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
import org.tinitalk.data.CallUnreadState
import org.tinitalk.data.CompatibilityProblem
import org.tinitalk.data.ContactCache
import org.tinitalk.data.ServerCompatibilityException
import org.tinitalk.data.SessionReplacedReason
import org.tinitalk.data.httpsServerUrl
import org.tinitalk.data.sameIdentity
import org.tinitalk.data.SharedPreferencesKeyValueStore
import org.tinitalk.network.NetworkAvailability
import org.tinitalk.network.networkAvailability
import org.tinitalk.permissions.AppPermissionsState
import org.tinitalk.push.DeviceIdentity
import org.tinitalk.push.IncomingCallNotifier
import org.tinitalk.push.AccountBadgeRefreshId
import org.tinitalk.telecom.CallForegroundService
import org.tinitalk.telecom.IncomingCallController
import org.tinitalk.telecom.OutgoingCallStartResult
import org.tinitalk.ui.MainScreen
import org.tinitalk.ui.MainScreenState
import org.tinitalk.ui.AccountPage
import org.tinitalk.ui.AccountSummary
import org.tinitalk.ui.ContactNameViewModel
import org.tinitalk.ui.ContactHistoryState
import org.tinitalk.ui.HistoryRefreshGate
import org.tinitalk.ui.HISTORY_PAGE_SIZE
import org.tinitalk.ui.LocalContactPhotoReader
import org.tinitalk.ui.accountHistoryWindow
import org.tinitalk.ui.isHistoryVisibleToUser
import org.tinitalk.ui.shouldMarkHistoryRead
import org.tinitalk.ui.isCurrentContactHistoryRequest
import org.tinitalk.ui.isCurrentSessionRequest
import org.tinitalk.ui.withOfflineSession
import org.tinitalk.ui.configuredAboutServerUrl
import org.tinitalk.ui.withPage
import org.tinitalk.ui.theme.TiniTalkTheme
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CompletableFuture

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
    private lateinit var contactCache: ContactCache
    private lateinit var network: NetworkAvailability
    private var screenState by mutableStateOf(MainScreenState())
    private var callUiState by mutableStateOf(CallUiStateStore.snapshot())
    private var loginResetKey by mutableIntStateOf(0)
    @Volatile
    private var mainScreenResumed = false
    private var historyLoadGeneration = 0
    private var historyVisible = false
    private val historyRefreshGate = HistoryRefreshGate()
    private var contactHistoryGeneration = 0
    private var contactHistoryLogin: String? = null
    private var contactHistoryAccountId: AccountId? = null
    private val contactHistoryRefreshGate = HistoryRefreshGate()
    private var authGeneration = 0
    private var contactsSyncing = false
    private val callUiObserver: (CallUiState) -> Unit = { state ->
        runOnUiThread { callUiState = state }
    }
    private val accountMissedCountObserver: (Int) -> Unit = { count ->
        runOnUiThread {
            if (!isDestroyed && screenState.unreadMissedCount != count) {
                screenState = screenState.copy(unreadMissedCount = count)
            }
        }
    }
    private val accountCallHistoryObserver: (AccountUnreadState) -> Unit = { unread ->
        mainHandler.post {
            if (!isDestroyed && screenState.signedIn) onCallHistoryChanged(unread)
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
        repository = ContactRepository(this, authStore, contactCache)
        network = networkAvailability()
        screenState = screenState.copy(networkAvailable = network.available)
        setContent {
            TiniTalkTheme(darkTheme = true) {
                CompositionLocalProvider(LocalContactPhotoReader provides (application as TinitalkApplication).contactPhotoStore) {
                    val contactNameUpdate = contactNameViewModel.state
                    val visibleScreenState = screenState.withContactUpdates(contactNameViewModel.updatedContacts)
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
                        onSignIn = ::loadContacts,
                        onCheckServer = repository::checkServer,
                        onCheckServerDetails = repository::checkServerDetails,
                        onRequestNotifications = ::requestNotificationPermission,
                        onRequestMicrophone = ::requestMicrophonePermission,
                        onRequestFullScreenCalls = ::requestFullScreenIntentPermission,
                        onRefreshPermissions = ::refreshPermissions,
                        onCall = ::startCall,
                        onRenameContact = { key, customName ->
                            if (network.available) {
                                contactNameViewModel.rename(repository, key, customName)
                            } else {
                                showNoInternetMessage()
                            }
                        },
                        onRenameHandled = contactNameViewModel::clearResult,
                        onOpenCall = { startActivity(CallActivity.ongoingIntent(this)) },
                        onContactsVisible = { historyVisible = false },
                        onRefreshContacts = ::refreshContacts,
                        onContactsRefreshMessageHandled = ::clearContactsRefreshMessage,
                        onHistoryVisible = ::showHistory,
                        onLoadMoreHistory = ::loadMoreHistory,
                        onContactHistoryVisible = ::showContactHistory,
                        onContactHistoryHidden = ::hideContactHistory,
                        onLoadMoreContactHistory = ::loadMoreContactHistory,
                        onRetryContactHistory = ::retryContactHistory,
                        onOpenProfile = { screenState = screenState.copy(accountPage = AccountPage.Profile) },
                        onCloseProfile = { screenState = screenState.copy(accountPage = AccountPage.Main) },
                        onOpenAddAccount = {
                            screenState = screenState.copy(accountPage = AccountPage.AddAccount, addAccountErrorMessage = null)
                        },
                        onCloseAddAccount = {
                            if (!screenState.addingAccount) screenState = screenState.copy(accountPage = AccountPage.Profile)
                        },
                        onAddAccount = ::addAccount,
                        onRemoveAccount = ::removeAccount,
                        onCheckAddAccountServer = repository::checkAddAccountServer,
                    )
                }
            }
        }
        CallUiStateStore.observe(callUiObserver)
        IncomingCallNotifier(this).observeAccountMissedCount(accountMissedCountObserver)
        CallHistoryEvents.observeAccount(accountCallHistoryObserver)
        AuthSessionEvents.observe(authSessionObserver)
        accountAdditionHandoff.observe(accountAdditionObserver)
        network.observe(networkObserver)
        refreshPermissions()
        restoreContacts()
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
        screenState = screenState.copy(signingIn = true, errorMessage = null)
        val serverUrl = checkNotNull(httpsServerUrl(url))
        val deviceId = DeviceIdentity.id(this)
        Thread {
            runCatching { repository.signIn(url, login, token, deviceId) }
                .onSuccess { page ->
                    runOnUiThread {
                        if (isCurrentSessionRequest(requestAuthGeneration, authGeneration)) {
                            val account = repository.accounts().singleOrNull {
                                it.session.url == serverUrl && it.session.login == login.trim()
                            }
                            if (account != null) {
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
                }
                .onFailure { showSessionErrorIfCurrent(it, requestAuthGeneration) }
        }.start()
    }

    private fun startCall(accountContact: AccountContact) {
        val contact = accountContact.contact
        val currentCall = CallServiceState.snapshot()
        if (currentCall.phase != CallPhase.Idle && currentCall.phase != CallPhase.Ended) {
            startActivity(CallActivity.ongoingIntent(this))
            return
        }
        when (val start = CallForegroundService.tryStartOutgoing(this, accountContact.peerKey, contact.displayName)) {
            is OutgoingCallStartResult.Started -> startActivity(
                CallActivity.outgoingIntent(this, accountContact.peerKey, contact.displayName, start.key),
            )
            is OutgoingCallStartResult.Busy -> {
                val pending = IncomingCallController().load(this)?.invite
                    ?.takeIf { it.owner == start.owner }
                if (pending != null) IncomingCallController().openScreen(this, pending)
                else startActivity(CallActivity.ongoingIntent(this))
            }
            OutgoingCallStartResult.Offline -> showNoInternetMessage()
            OutgoingCallStartResult.Unavailable ->
                Toast.makeText(this, "\u041d\u0435 \u0443\u0434\u0430\u043b\u043e\u0441\u044c \u043d\u0430\u0447\u0430\u0442\u044c \u0437\u0432\u043e\u043d\u043e\u043a", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showContacts(pages: List<AccountContactPage>) {
        runOnUiThread {
            authGeneration++
            historyLoadGeneration++
            contactHistoryGeneration++
            contactHistoryLogin = null
            contactHistoryAccountId = null
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
                accountHistory = emptyList(),
                historyLoaded = false,
                historyLoading = false,
                historyLoadingMore = false,
                historyNextBefores = emptyMap(),
                historyVisibleLimit = HISTORY_PAGE_SIZE,
                historyUnavailableAccounts = emptySet(),
                historyErrorMessage = null,
                contactHistory = ContactHistoryState(),
                unreadMissedCount = 0,
                unreadByAccount = emptyMap(),
                errorMessage = null,
            )
            IncomingCallNotifier(this).syncMissedAccounts(accountOrder)
            refreshPermissions()
            refreshMissedCount()
        }
    }

    private fun refreshContacts(showProgress: Boolean = true) {
        if (!network.available || !screenState.signedIn || contactsSyncing) return
        val requestAuthGeneration = authGeneration
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
                runCatching { repository.refreshContacts(account.id) }.getOrNull()
            }
        }
        CompletableFuture.allOf(*requests.toTypedArray()).whenComplete { _, _ ->
            val pages = requests.mapNotNull { request -> runCatching { request.getNow(null) }.getOrNull() }
            runOnUiThread {
                contactsSyncing = false
                if (!screenState.signedIn || !isCurrentSessionRequest(requestAuthGeneration, authGeneration)) {
                    return@runOnUiThread
                }
                val activeAccounts = repository.accounts().filter { current ->
                    accounts.any { it.id == current.id && it.session.sameIdentity(current.session) }
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

    private fun showHistory() {
        historyVisible = true
        loadHistory(reset = true, markRead = true)
    }

    private fun loadMoreHistory() {
        val window = accountHistoryWindow(
            loaded = screenState.accountHistory,
            visibleLimit = screenState.historyVisibleLimit,
            cursors = screenState.historyNextBefores,
            unavailableAccounts = screenState.historyUnavailableAccounts,
        )
        if (!window.hasMore) return
        loadHistory(reset = false)
    }

    private fun loadHistory(reset: Boolean, markRead: Boolean = false) {
        if (!network.available || !screenState.signedIn) return
        val requestAuthGeneration = authGeneration
        val generation: Int
        val targetVisibleLimit: Int
        if (reset) {
            if (screenState.historyLoading) return
            historyLoadGeneration++
            generation = historyLoadGeneration
            targetVisibleLimit = HISTORY_PAGE_SIZE
            screenState = screenState.copy(historyLoading = true, historyErrorMessage = null)
        } else {
            if (screenState.historyLoading || screenState.historyLoadingMore) return
            generation = historyLoadGeneration
            targetVisibleLimit = screenState.historyVisibleLimit + HISTORY_PAGE_SIZE
            screenState = screenState.copy(historyLoadingMore = true, historyErrorMessage = null)
        }
        val accounts = repository.accounts()
        if (accounts.isEmpty()) {
            screenState = screenState.copy(historyLoading = false, historyLoadingMore = false)
            return
        }
        val accountIdsSnapshot = accounts.map { it.id }
        val requestedCursors = screenState.historyNextBefores.toMap()
        val cachedHistory = screenState.accountHistory.groupBy { it.accountId }
        val unavailableSnapshot = screenState.historyUnavailableAccounts
        val requestAccounts = accounts.filter { account ->
            reset || (account.id !in unavailableSnapshot && (requestedCursors[account.id] ?: 0L) > 0L)
        }
        if (!reset && requestAccounts.isEmpty()) {
            screenState = screenState.copy(
                historyVisibleLimit = targetVisibleLimit,
                historyLoadingMore = false,
            )
            return
        }
        val notifier = IncomingCallNotifier(this).also { it.syncMissedAccounts(accountIdsSnapshot) }
        val badgeRefreshes = requestAccounts.associate { account ->
            account.id to notifier.beginAccountMissedCountRefresh(account.id)
        }
        val requests = requestAccounts.associateWith { account ->
            CompletableFuture.supplyAsync {
                val before = if (reset) 0L else requestedCursors[account.id] ?: 0L
                runCatching {
                    repository.loadCallHistory(
                        account.id,
                        before = before,
                        expectedSession = account.session,
                    )
                }.getOrNull()
            }
        }
        CompletableFuture.allOf(*requests.values.toTypedArray()).whenComplete { _, _ ->
            val pages = requests.values.mapNotNull { request ->
                runCatching { request.getNow(null) }.getOrNull()
            }
            runOnUiThread {
                val activeRecords = repository.accounts()
                val active = activeRecords.associate { it.id to it.session }
                if (!screenState.signedIn || generation != historyLoadGeneration ||
                    !isCurrentSessionRequest(requestAuthGeneration, authGeneration)
                ) return@runOnUiThread
                val activeOrder = activeRecords.filter { record ->
                    accounts.any { it.id == record.id && it.session.sameIdentity(record.session) }
                }.map { it.id }
                val activePages = pages.filter { page ->
                    val activeSession = active[page.accountId]
                    page.accountId in activeOrder && page.session?.sameIdentity(activeSession) == true
                }
                val requestedActiveIds = requestAccounts.filter { requested ->
                    active[requested.id]?.sameIdentity(requested.session) == true
                }.map { it.id }.toSet()
                val successfulIds = activePages.map { it.accountId }.toSet()
                val unavailable = (
                    (if (reset) emptySet() else unavailableSnapshot) +
                        (requestedActiveIds - successfulIds) - successfulIds
                    ).intersect(activeOrder.toSet())
                val reduced = org.tinitalk.ui.reduceAccountHistory(
                    activeOrder, cachedHistory, requestedCursors, activePages, append = !reset,
                )
                val combined = reduced.items
                screenState = screenState.copy(
                    accountHistory = combined,
                    historyLoaded = true,
                    historyLoading = false,
                    historyLoadingMore = false,
                    historyNextBefores = reduced.cursors,
                    historyVisibleLimit = targetVisibleLimit,
                    historyUnavailableAccounts = unavailable,
                    historyErrorMessage = if (combined.isEmpty() && unavailable.isNotEmpty()) {
                        "Не удалось загрузить историю со всех серверов"
                    } else {
                        null
                    },
                )
                notifier.syncMissedAccounts(activeOrder)
                activePages.forEach { page ->
                    val session = page.session ?: return@forEach
                    applyUnreadMissedState(
                        page.accountId,
                        page.unread,
                        badgeRefreshes[page.accountId],
                        CallSessionBinding.from(session),
                    )
                }
                finishHistoryRefresh()
                if (reset && shouldMarkHistoryRead(markRead, mainScreenResumed, historyVisible)) {
                    markActiveHistoryPages(activePages)
                }
            }
        }
    }

    private fun showContactHistory(key: AccountPeerKey) {
        val login = key.login
        historyVisible = false
        if (contactHistoryLogin == login &&
            contactHistoryAccountId == key.accountId &&
            screenState.contactHistory.peerLogin == login &&
            (screenState.contactHistory.loaded || screenState.contactHistory.loading)
        ) {
            return
        }
        contactHistoryLogin = login
        contactHistoryAccountId = key.accountId
        loadContactHistory(login, reset = true, markRead = true)
    }

    private fun hideContactHistory() {
        if (contactHistoryLogin == null && screenState.contactHistory.peerLogin == null) return
        contactHistoryRefreshGate.clear()
        contactHistoryLogin = null
        contactHistoryAccountId = null
        contactHistoryGeneration++
        screenState = screenState.copy(contactHistory = ContactHistoryState())
    }

    private fun loadMoreContactHistory() {
        contactHistoryLogin?.let { loadContactHistory(it, reset = false) }
    }

    private fun retryContactHistory() {
        val login = contactHistoryLogin ?: return
        val history = screenState.contactHistory
        loadContactHistory(
            login,
            reset = history.items.isEmpty() || history.nextBefore == 0L,
            markRead = true,
        )
    }

    private fun loadContactHistory(login: String, reset: Boolean, markRead: Boolean = false) {
        val accountId = contactHistoryAccountId ?: return
        val accountSession = repository.accounts().firstOrNull { it.id == accountId }?.session ?: return
        if (!network.available || !screenState.signedIn || contactHistoryLogin != login) return
        val before: Long
        val generation: Int
        val requestAuthGeneration = authGeneration
        if (reset) {
            if (screenState.contactHistory.accountId == accountId &&
                screenState.contactHistory.peerLogin == login &&
                screenState.contactHistory.loading
            ) {
                return
            }
            contactHistoryGeneration++
            generation = contactHistoryGeneration
            before = 0
            screenState = screenState.copy(
                contactHistory = ContactHistoryState(accountId = accountId, peerLogin = login, loading = true),
            )
        } else {
            val history = screenState.contactHistory
            before = history.nextBefore
            if (history.accountId != accountId || history.peerLogin != login || before == 0L ||
                history.loading || history.loadingMore
            ) {
                return
            }
            generation = contactHistoryGeneration
            screenState = screenState.copy(
                contactHistory = history.copy(loadingMore = true, errorMessage = null),
            )
        }
        val badgeRefreshId = IncomingCallNotifier(this).beginAccountMissedCountRefresh(accountId)
        Thread {
            runCatching { repository.loadCallHistory(accountId, before = before, peerLogin = login) }
                .onSuccess { page ->
                    if (page == null) return@onSuccess
                    val rawPage = org.tinitalk.data.CallHistoryPage(
                        page.items.map { it.item }, page.nextBefore, page.latestId,
                        page.unread.unreadMissedCount, page.unread.unreadMissed,
                    )
                    runOnUiThread {
                        if (!screenState.signedIn ||
                            !repository.accounts().firstOrNull { it.id == accountId }?.session.sameIdentity(accountSession) ||
                            !isCurrentSessionRequest(requestAuthGeneration, authGeneration) ||
                            !isCurrentContactHistoryRequest(
                                generation,
                                contactHistoryGeneration,
                                accountId,
                                contactHistoryAccountId,
                                login,
                                contactHistoryLogin,
                            )
                        ) {
                            return@runOnUiThread
                        }
                        applyUnreadMissedState(
                            page.accountId,
                            page.unread,
                            badgeRefreshId,
                            CallSessionBinding.from(accountSession),
                        )
                        screenState = screenState.copy(
                            contactHistory = screenState.contactHistory.withPage(login, rawPage, reset),
                        )
                        if (reset && page.latestId > 0 &&
                            shouldMarkHistoryRead(
                                markRead,
                                mainScreenResumed,
                                contactHistoryAccountId == accountId && contactHistoryLogin == login,
                            )
                        ) {
                            markContactHistoryRead(
                                accountId,
                                login,
                                page.latestId,
                                generation,
                                requestAuthGeneration,
                            )
                        }
                        finishContactHistoryRefresh(login)
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
                                    accountId,
                                    contactHistoryAccountId,
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
                            finishContactHistoryRefresh(login)
                        }
                    }
                }
        }.start()
    }

    private fun markContactHistoryRead(
        accountId: AccountId,
        login: String,
        throughId: Long,
        generation: Int,
        requestAuthGeneration: Int,
    ) {
        if (!isHistoryVisibleToUser(
                mainScreenResumed,
                contactHistoryAccountId == accountId && contactHistoryLogin == login,
            ) ||
            !network.available ||
            !screenState.signedIn ||
            !isCurrentSessionRequest(requestAuthGeneration, authGeneration) ||
            !isCurrentContactHistoryRequest(
                generation,
                contactHistoryGeneration,
                accountId,
                contactHistoryAccountId,
                login,
                contactHistoryLogin,
            )
        ) {
            return
        }
        val accountSession = repository.accounts().firstOrNull { it.id == accountId }?.session ?: return
        val badgeRefreshId = IncomingCallNotifier(this).beginAccountMissedCountRefresh(accountId)
        Thread {
            runCatching {
                repository.markCallHistoryRead(
                    accountId,
                    throughId,
                    peerLogin = login,
                    expectedSession = accountSession,
                )
            }
                .onSuccess { unread ->
                    if (unread == null) return@onSuccess
                    runOnUiThread {
                        if (!screenState.signedIn ||
                            !repository.accounts().firstOrNull { it.id == accountId }?.session.sameIdentity(accountSession) ||
                            !unread.session.sameIdentity(accountSession) ||
                            !isCurrentSessionRequest(requestAuthGeneration, authGeneration)
                        ) {
                            return@runOnUiThread
                        }
                        authStore.withCurrent(accountId, accountSession) {
                            applyUnreadMissedState(
                                unread.accountId,
                                unread.unread,
                                badgeRefreshId,
                                CallSessionBinding.from(accountSession),
                            )
                        }
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
            if (error is ApiException && error.code == 401 && authStore.list().isNotEmpty()) return@runOnUiThread
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

    private fun addAccount(url: String, login: String, token: String) {
        if (!network.available || screenState.addingAccount) return
        screenState = screenState.copy(addingAccount = true, addAccountErrorMessage = null)
        val deviceId = DeviceIdentity.id(this)
        Thread {
            runCatching { repository.addAccount(url, login, token, deviceId) }
                .onSuccess { added ->
                    accountAdditionHandoff.publish(
                        AccountAdditionOutcome.Added(
                            accountId = added.account.id,
                            sessionId = added.account.session.sessionId,
                            configId = added.account.session.configId,
                            contacts = added.contacts,
                        ),
                    )
                }.onFailure { error ->
                    accountAdditionHandoff.publish(AccountAdditionOutcome.Failed(userErrorMessage(error)))
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
                screenState = screenState.copy(
                    restoring = false,
                    signingIn = false,
                    signedIn = accounts.isNotEmpty(),
                    addingAccount = false,
                    accountPage = AccountPage.AddAccount,
                    addAccountErrorMessage = outcome.message,
                    serverUrl = accounts.aboutServerUrl(),
                    accounts = accounts.toAccountSummaries(),
                )
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
            addAccountErrorMessage = null,
            serverUrl = accounts.aboutServerUrl(),
            accounts = accounts.toAccountSummaries(),
            accountContacts = org.tinitalk.ui.mergeAccountContacts(
                accounts.map { it.id },
                accounts.associate { it.id to contactCache.load(it).items },
            ),
        )
        IncomingCallNotifier(this).syncMissedAccounts(accounts.map { it.id })
        refreshMissedCount()
    }

    private fun removeAccount(accountId: AccountId) {
        if (!repository.removeAccount(accountId)) {
            screenState = screenState.copy(accounts = repository.accounts().toAccountSummaries())
            return
        }
        val remaining = repository.accounts()
        if (remaining.isEmpty()) {
            resetToLogin()
            return
        }
        pruneRemovedAccount(accountId, remaining)
    }

    private fun pruneRemovedAccount(accountId: AccountId, remaining: List<org.tinitalk.data.AccountRecord>) {
        if (contactHistoryAccountId == accountId) {
            contactHistoryGeneration++
            contactHistoryRefreshGate.clear()
            contactHistoryAccountId = null
            contactHistoryLogin = null
        }
        screenState = screenState.copy(
            accountContacts = screenState.accountContacts.filterNot { it.accountId == accountId },
            historyNextBefores = screenState.historyNextBefores - accountId,
            historyUnavailableAccounts = screenState.historyUnavailableAccounts - accountId,
            accountHistory = screenState.accountHistory.filterNot { it.accountId == accountId },
            unreadByAccount = screenState.unreadByAccount - accountId,
            latestUnreadMissedByAccountContact = screenState.latestUnreadMissedByAccountContact.filterKeys { it.accountId != accountId },
            contactHistory = if (contactHistoryAccountId == null) ContactHistoryState() else screenState.contactHistory,
            serverUrl = remaining.aboutServerUrl(),
            accounts = remaining.toAccountSummaries(),
        )
        IncomingCallNotifier(this).syncMissedAccounts(remaining.map { it.id })
    }

    private fun resetToLogin(errorMessage: String? = null) {
        authGeneration++
        contactNameViewModel.reset()
        historyLoadGeneration++
        contactHistoryGeneration++
        contactHistoryLogin = null
        contactHistoryAccountId = null
        historyVisible = false
        historyRefreshGate.clear()
        contactHistoryRefreshGate.clear()
        loginResetKey++
        screenState = MainScreenState(
            restoring = false,
            permissions = screenState.permissions,
            errorMessage = errorMessage,
            networkAvailable = network.available,
        )
    }

    private fun showOfflineAccounts() {
        val accounts = repository.accounts().filter { repository.restorableSession(it.id) != null }
        screenState = screenState.withOfflineSession(
            serverUrl = accounts.aboutServerUrl(),
            signedIn = accounts.isNotEmpty(),
        ).copy(accounts = accounts.toAccountSummaries())
    }

    private fun clearInFlightPresentationAfterAccountAdd() {
        historyLoadGeneration++
        contactHistoryGeneration++
        historyRefreshGate.clear()
        contactHistoryRefreshGate.clear()
        screenState = screenState.copy(
            contactsRefreshing = false,
            contactsRefreshErrorMessage = null,
            historyLoading = false,
            historyLoadingMore = false,
            historyErrorMessage = null,
            contactHistory = screenState.contactHistory.copy(loading = false, loadingMore = false, errorMessage = null),
        )
    }

    override fun onResume() {
        super.onResume()
        mainScreenResumed = true
        consumeAccountAdditionIfResumed()
        refreshPermissions()
        when {
            contactHistoryLogin != null -> loadContactHistory(contactHistoryLogin.orEmpty(), reset = true)
            historyVisible -> loadHistory(reset = true)
            else -> refreshMissedCount()
        }
    }

    override fun onPause() {
        mainScreenResumed = false
        super.onPause()
    }

    private fun refreshMissedCount() {
        if (!network.available || !screenState.signedIn) return
        val requestAuthGeneration = authGeneration
        val generation = historyLoadGeneration
        val accounts = repository.accounts()
        val notifier = IncomingCallNotifier(this)
        notifier.syncMissedAccounts(accounts.map { it.id })
        val refreshes = accounts.associate { it.id to notifier.beginAccountMissedCountRefresh(it.id) }
        Thread {
            val pages = accounts.mapNotNull { account ->
                runCatching { repository.loadCallHistory(account.id, limit = 1, expectedSession = account.session) }.getOrNull()
            }
            runOnUiThread {
                if (!screenState.signedIn || generation != historyLoadGeneration ||
                    !isCurrentSessionRequest(requestAuthGeneration, authGeneration)) return@runOnUiThread
                pages.forEach { page ->
                    val session = page.session ?: return@forEach
                    authStore.withCurrent(page.accountId, session) {
                        applyUnreadMissedState(
                            page.accountId,
                            page.unread,
                            refreshes[page.accountId],
                            CallSessionBinding.from(session),
                        )
                    }
                }
            }
        }.start()
    }

    private fun applyUnreadMissedState(
        accountId: AccountId,
        unread: CallUnreadState,
        badgeRefreshId: AccountBadgeRefreshId?,
        redialBinding: CallSessionBinding,
    ) {
        val notifier = IncomingCallNotifier(this)
        notifier.syncMissedAccounts(repository.accounts().map { it.id })
        val update = notifier.updateAccountMissedState(
            accountId,
            unread,
            badgeRefreshId,
            redialBinding = redialBinding,
        )
        if (update.applied) {
            val unreadByAccount = screenState.unreadByAccount + (accountId to unread)
            val presentation = org.tinitalk.ui.aggregateUnreadMissed(unreadByAccount)
            screenState = screenState.copy(
                unreadByAccount = unreadByAccount,
                unreadMissedCount = update.count,
                latestUnreadMissedByAccountContact = presentation.latestByContact,
            )
        }
    }

    private fun onCallHistoryChanged(update: AccountUnreadState) {
        val current = repository.accounts().firstOrNull { it.id == update.accountId } ?: return
        if (!acceptsAccountUnreadUpdate(current.session, update)) return
        val notifier = IncomingCallNotifier(this)
        notifier.syncMissedAccounts(repository.accounts().map { it.id })
        val refreshId = notifier.beginAccountMissedCountRefresh(update.accountId)
        val badgeUpdate = notifier.updateAccountMissedState(
            update.accountId,
            update.unread,
            refreshId,
            redialBinding = update.session?.let(CallSessionBinding::from),
        )
        if (!badgeUpdate.applied) return
        val unreadByAccount = screenState.unreadByAccount + (update.accountId to update.unread)
        val presentation = org.tinitalk.ui.aggregateUnreadMissed(unreadByAccount)
        screenState = screenState.copy(
            unreadByAccount = unreadByAccount,
            unreadMissedCount = badgeUpdate.count,
            latestUnreadMissedByAccountContact = presentation.latestByContact,
        )
        if (!mainScreenResumed) return
        when {
            contactHistoryLogin != null -> requestContactHistoryRefresh(contactHistoryLogin.orEmpty())
            historyVisible -> requestHistoryRefresh()
        }
    }

    private fun requestHistoryRefresh() {
        if (!network.available || !isHistoryVisibleToUser(mainScreenResumed, historyVisible)) return
        if (historyRefreshGate.request(screenState.historyLoading || screenState.historyLoadingMore)) {
            loadHistory(reset = true)
        }
    }

    private fun finishHistoryRefresh() {
        if (historyRefreshGate.afterLoad() && network.available &&
            isHistoryVisibleToUser(mainScreenResumed, historyVisible)
        ) {
            loadHistory(reset = true)
        }
    }

    private fun requestContactHistoryRefresh(login: String) {
        if (!network.available ||
            !isHistoryVisibleToUser(mainScreenResumed, contactHistoryLogin == login)
        ) {
            return
        }
        val history = screenState.contactHistory
        if (contactHistoryRefreshGate.request(history.loading || history.loadingMore)) {
            loadContactHistory(login, reset = true)
        }
    }

    private fun finishContactHistoryRefresh(login: String) {
        if (contactHistoryRefreshGate.afterLoad() && network.available &&
            isHistoryVisibleToUser(mainScreenResumed, contactHistoryLogin == login)
        ) {
            loadContactHistory(login, reset = true)
        }
    }

    override fun onDestroy() {
        network.removeObserver(networkObserver)
        accountAdditionHandoff.removeObserver(accountAdditionObserver)
        AuthSessionEvents.removeObserver(authSessionObserver)
        CallHistoryEvents.removeAccountObserver(accountCallHistoryObserver)
        IncomingCallNotifier(this).removeAccountMissedCountObserver(accountMissedCountObserver)
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
                    .setData(Uri.parse("package:$packageName")),
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
        val session = repository.restorableSession() ?: return
        refreshPermissions()
        if (!screenState.signedIn || screenState.accountContacts.isEmpty()) {
            screenState = screenState.copy(restoring = true)
            restoreContacts()
            return
        }
        refreshContacts(showProgress = false)
        when {
            contactHistoryLogin != null -> loadContactHistory(contactHistoryLogin.orEmpty(), reset = true)
            historyVisible -> loadHistory(reset = true)
            else -> refreshMissedCount()
        }
    }

    private fun showNoInternetMessage() {
        Toast.makeText(this, "Нет подключения к интернету", Toast.LENGTH_SHORT).show()
    }
    private fun markActiveHistoryPages(
        pages: List<org.tinitalk.data.AccountCallHistoryPage>,
    ) {
        val readablePages = pages.filter { it.latestId > 0 }
        val notifier = IncomingCallNotifier(this)
        val refreshes = readablePages.associate { page ->
            page.accountId to notifier.beginAccountMissedCountRefresh(page.accountId)
        }
        Thread {
            markEachAccountHistoryPage(readablePages) { page ->
                repository.markCallHistoryRead(page.accountId, page.latestId, expectedSession = page.session)
            }.forEach { update ->
                runOnUiThread {
                    val current = repository.accounts().firstOrNull { it.id == update.accountId } ?: return@runOnUiThread
                    if (!acceptsAccountUnreadUpdate(current.session, update)) return@runOnUiThread
                    val session = update.session ?: return@runOnUiThread
                    authStore.withCurrent(update.accountId, session) {
                        applyUnreadMissedState(
                            update.accountId,
                            update.unread,
                            refreshes[update.accountId],
                            CallSessionBinding.from(session),
                        )
                    }
                }
            }
        }.start()
    }
}

internal fun acceptsAccountUnreadUpdate(currentSession: org.tinitalk.data.Session, update: AccountUnreadState): Boolean =
    update.session == null || update.session.sameIdentity(currentSession)

private fun List<org.tinitalk.data.AccountRecord>.toAccountSummaries(): List<AccountSummary> = map { account ->
    AccountSummary(account.id, account.session.url, account.session.login, account.displayName)
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
    is ApiException -> if (error.code == 401) "Неверный логин или токен" else "Сервер вернул ошибку ${error.code}"
    else -> "Не удалось подключиться к серверу"
}

internal fun markEachAccountHistoryPage(
    pages: List<org.tinitalk.data.AccountCallHistoryPage>,
    mark: (org.tinitalk.data.AccountCallHistoryPage) -> AccountUnreadState?,
): List<AccountUnreadState> = pages.mapNotNull { page -> runCatching { mark(page) }.getOrNull() }

private fun MainScreenState.withContactUpdates(updates: Map<org.tinitalk.data.AccountPeerKey, Contact>): MainScreenState {
    if (updates.isEmpty()) return this
    val sortedAccountContacts = org.tinitalk.ui.sortAccountContacts(
        accountContacts.map { accountContact ->
            updates[accountContact.peerKey]?.let { accountContact.copy(contact = it) } ?: accountContact
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
