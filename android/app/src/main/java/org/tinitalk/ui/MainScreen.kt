package org.tinitalk.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.tinitalk.BuildConfig
import org.tinitalk.ContactOpenRequest
import org.tinitalk.R
import org.tinitalk.call.CallDirection
import org.tinitalk.call.CallPhase
import org.tinitalk.call.CallUiState
import org.tinitalk.call.ConnectionHealth
import org.tinitalk.data.AccountContact
import org.tinitalk.data.AccountCallHistoryPage
import org.tinitalk.data.AccountHistory
import org.tinitalk.data.AccountId
import org.tinitalk.data.normalizeServerUrl
import java.net.URI
import org.tinitalk.data.AccountPeerKey
import org.tinitalk.data.CallHistoryItem
import org.tinitalk.data.CallUnreadState
import org.tinitalk.data.Contact
import org.tinitalk.data.NormalizedCropSquare
import org.tinitalk.data.ServerCheckDetails
import org.tinitalk.data.ServerCheckResult
import org.tinitalk.permissions.AppPermissionsState
import org.tinitalk.ui.theme.BrandBackground
import org.tinitalk.ui.theme.BrandGold
import org.tinitalk.ui.theme.CallAnswerGreen
import org.tinitalk.ui.theme.CallRejectRed
import java.text.Collator
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val HISTORY_PAGE_SIZE = 50

internal enum class ServerCheckIndicator {
    Checking,
    Available,
    Unavailable,
    Incompatible,
}

internal data class ServerCheckPresentation(
    val indicator: ServerCheckIndicator,
    val message: String,
)

internal fun serverCheckPresentation(
    serverReady: Boolean,
    checking: Boolean,
    result: ServerCheckResult?,
    internetAvailable: Boolean = true,
): ServerCheckPresentation = when {
    !internetAvailable ->
        ServerCheckPresentation(ServerCheckIndicator.Unavailable, "Нет подключения к интернету")
    !serverReady -> ServerCheckPresentation(ServerCheckIndicator.Unavailable, "Введите адрес сервера")
    checking -> ServerCheckPresentation(ServerCheckIndicator.Checking, "Проверяем подключение…")
    result == null -> ServerCheckPresentation(ServerCheckIndicator.Checking, "Проверяем подключение…")
    result == ServerCheckResult.Available ->
        ServerCheckPresentation(ServerCheckIndicator.Available, "Сервер TiniTalk доступен")
    result == ServerCheckResult.WrongServer ->
        ServerCheckPresentation(ServerCheckIndicator.Unavailable, "По этому адресу нет сервера TiniTalk")
    result == ServerCheckResult.ServerOutdated ->
        ServerCheckPresentation(ServerCheckIndicator.Incompatible, "Сервер несовместим с этой версией приложения")
    result == ServerCheckResult.AppOutdated ->
        ServerCheckPresentation(ServerCheckIndicator.Incompatible, "Приложение TiniTalk устарело. Установите новую версию")
    else -> ServerCheckPresentation(
        ServerCheckIndicator.Unavailable,
        "Сервер недоступен. Проверьте адрес и подключение к сети",
    )
}

data class MainScreenState(
    val restoring: Boolean = true,
    val signingIn: Boolean = false,
    val signedIn: Boolean = false,
    val serverUrl: String = "",
    val accountContacts: List<AccountContact> = emptyList(),
    val contactsRefreshing: Boolean = false,
    val contactsRefreshErrorMessage: String? = null,
    val accountHistory: List<AccountHistory> = emptyList(),
    val historyLoaded: Boolean = false,
    val historyLoading: Boolean = false,
    val historyLoadingMore: Boolean = false,
    val historyNextBefores: Map<AccountId, Long> = emptyMap(),
    val historyVisibleLimit: Int = HISTORY_PAGE_SIZE,
    val historyUnavailableAccounts: Set<AccountId> = emptySet(),
    val historyErrorMessage: String? = null,
    val contactHistory: ContactHistoryState = ContactHistoryState(),
    val unreadMissedCount: Int = 0,
    val unreadByAccount: Map<AccountId, CallUnreadState> = emptyMap(),
    val latestUnreadMissedByAccountContact: Map<AccountPeerKey, Long> = emptyMap(),
    val permissions: AppPermissionsState = AppPermissionsState(),
    val errorMessage: String? = null,
    val networkAvailable: Boolean = true,
    val accountPage: AccountPage = AccountPage.Main,
    val accounts: List<AccountSummary> = emptyList(),
    val addingAccount: Boolean = false,
    val addAccountErrorMessage: String? = null,
)

enum class AccountPage { Main, Profile, AddAccount }

data class AccountSummary(
    val id: AccountId,
    val serverUrl: String,
    val login: String,
    val displayName: String?,
)

internal fun contactsRequiringServerSubtitle(contacts: List<AccountContact>): Set<AccountPeerKey> =
    contacts
        .groupBy { contactDisplayName(it.displayName).lowercase(Locale.ROOT) }
        .values
        .asSequence()
        .filter { group ->
            group
                .map { normalizeServerUrl(it.serverUrl).lowercase(Locale.ROOT) }
                .distinct()
                .size > 1
        }
        .flatten()
        .map(AccountContact::peerKey)
        .toSet()

internal fun configuredAboutServerUrl(serverUrls: List<String>): String =
    serverUrls.map(::normalizeServerUrl).distinct().singleOrNull().orEmpty()

internal fun serverHostname(serverUrl: String): String =
    runCatching { URI(normalizeServerUrl(serverUrl)).host }.getOrNull()?.takeIf(String::isNotBlank)
        ?: normalizeServerUrl(serverUrl)

internal fun serverAddress(serverUrl: String): String =
    normalizeServerUrl(serverUrl).replaceFirst(Regex("^https://", RegexOption.IGNORE_CASE), "")

fun MainScreenState.withOfflineSession(serverUrl: String?, signedIn: Boolean = serverUrl != null): MainScreenState = copy(
    restoring = false,
    signingIn = false,
    signedIn = signedIn,
    serverUrl = serverUrl.orEmpty(),
    contactsRefreshing = false,
    historyLoading = false,
    historyLoadingMore = false,
    contactHistory = contactHistory.copy(loading = false, loadingMore = false),
    networkAvailable = false,
)

internal fun shouldReturnToContactsOnBack(currentPage: Int, contactOpen: Boolean): Boolean =
    currentPage == 1 && !contactOpen

internal data class AccountUnreadPresentation(
    val latestByContact: Map<AccountPeerKey, Long>,
)

internal fun mergeAccountContacts(
    accountOrder: List<AccountId>,
    contactsByAccount: Map<AccountId, List<AccountContact>>,
): List<AccountContact> = sortAccountContacts(
    accountOrder.flatMap { accountId -> contactsByAccount[accountId].orEmpty() },
)

internal fun sortAccountContacts(contacts: List<AccountContact>): List<AccountContact> {
    val names = Collator.getInstance(Locale.forLanguageTag("ru")).apply { strength = Collator.PRIMARY }
    return contacts.sortedWith(Comparator { first, second ->
        names.compare(first.displayName.trim(), second.displayName.trim())
            .takeIf { it != 0 }
            ?: names.compare(first.login, second.login).takeIf { it != 0 }
            ?: first.serverUrl.compareTo(second.serverUrl)
                .takeIf { it != 0 }
            ?: first.accountId.value.compareTo(second.accountId.value)
    })
}

/** Stable Compose identity; length prefixes avoid delimiter and concatenation collisions. */
internal fun accountScopedKey(accountId: AccountId, value: String): String =
    "${accountId.value.length}:${accountId.value}${value.length}:$value"

/** Same contract as contacts, with account-bound history IDs kept distinct across servers. */
internal fun reduceAccountHistory(
    accountOrder: List<AccountId>,
    cached: Map<AccountId, List<AccountHistory>>,
    cursors: Map<AccountId, Long>,
    pages: List<AccountCallHistoryPage>,
    append: Boolean,
): AccountHistoryReduction {
    val allowed = accountOrder.toSet()
    val histories = cached.filterKeys(allowed::contains).toMutableMap()
    val next = cursors.filterKeys(allowed::contains).toMutableMap()
    pages.filter { it.accountId in allowed }.forEach { page ->
        histories[page.accountId] = if (append) {
            (histories[page.accountId].orEmpty() + page.items).distinctBy { it.key }
        } else page.items
        next[page.accountId] = page.nextBefore
    }
    val items = accountOrder.flatMap { histories[it].orEmpty() }.sortedWith(
        compareByDescending<AccountHistory> { it.startedAt }
            .thenBy { accountOrder.indexOf(it.accountId) }
            .thenByDescending { it.id },
    )
    return AccountHistoryReduction(items, next)
}

internal data class AccountHistoryReduction(
    val items: List<AccountHistory>,
    val cursors: Map<AccountId, Long>,
)

internal data class AccountHistoryWindow(
    val items: List<AccountHistory>,
    val hasMore: Boolean,
)

internal fun accountHistoryWindow(
    loaded: List<AccountHistory>,
    visibleLimit: Int,
    cursors: Map<AccountId, Long>,
    unavailableAccounts: Set<AccountId>,
): AccountHistoryWindow = AccountHistoryWindow(
    items = loaded.take(visibleLimit),
    hasMore = loaded.size > visibleLimit || cursors.any { (accountId, cursor) ->
        cursor > 0L && accountId !in unavailableAccounts
    },
)

internal fun aggregateUnreadMissed(
    unreadByAccount: Map<AccountId, CallUnreadState>,
): AccountUnreadPresentation = AccountUnreadPresentation(
    latestByContact = unreadByAccount.flatMap { (accountId, unread) ->
        unread.unreadMissed.map { missed -> AccountPeerKey(accountId, missed.peerLogin) to missed.startedAt }
    }.toMap(),
)

@Composable
fun MainScreen(
    state: MainScreenState,
    contactNameUpdate: ContactNameUpdateState,
    ongoingCall: CallUiState?,
    loginResetKey: Int,
    contactOpenRequest: ContactOpenRequest? = null,
    onContactOpenRequestHandled: (ContactOpenRequest) -> Unit = {},
    onSignIn: (url: String, login: String, token: String) -> Unit,
    onCheckServer: (url: String) -> ServerCheckResult,
    onCheckServerDetails: (url: String) -> ServerCheckDetails,
    onRequestNotifications: () -> Unit,
    onRequestMicrophone: () -> Unit,
    onRequestFullScreenCalls: () -> Unit,
    onRefreshPermissions: () -> Unit,
    onCall: (AccountContact) -> Unit,
    onRenameContact: (key: AccountPeerKey, customName: String?) -> Unit,
    onRenameHandled: () -> Unit,
    onOpenCall: () -> Unit,
    onContactsVisible: () -> Unit,
    onRefreshContacts: () -> Unit,
    onContactsRefreshMessageHandled: () -> Unit,
    onHistoryVisible: () -> Unit,
    onLoadMoreHistory: () -> Unit,
    onContactHistoryVisible: (AccountPeerKey) -> Unit,
    onContactHistoryHidden: () -> Unit,
    onLoadMoreContactHistory: () -> Unit,
    onRetryContactHistory: () -> Unit,
    contactPhotoEditorState: ContactPhotoEditorState = ContactPhotoEditorState(),
    onContactPhotoTargetVisible: (ContactPhotoEditTarget) -> Unit = {},
    onContactPhotoTargetHidden: (ContactPhotoEditTarget) -> Unit = {},
    onChooseContactPhoto: (ContactPhotoEditTarget, ContactPhotoSource) -> Unit = { _, _ -> },
    onRemoveContactPhoto: (ContactPhotoEditTarget) -> Unit = {},
    onCancelContactPhotoCrop: () -> Unit = {},
    onConfirmContactPhotoCrop: (NormalizedCropSquare) -> Unit = {},
    onContactPhotoMessageShown: () -> Unit = {},
    onOpenProfile: () -> Unit,
    onCloseProfile: () -> Unit,
    onOpenAddAccount: () -> Unit,
    onCloseAddAccount: () -> Unit,
    onAddAccount: (url: String, login: String, token: String) -> Unit,
    onRemoveAccount: (AccountId) -> Unit,
    onCheckAddAccountServer: (String) -> ServerCheckResult = onCheckServer,
) {
    var aboutVisible by rememberSaveable(state.signedIn) { mutableStateOf(false) }
    LaunchedEffect(contactOpenRequest) {
        if (contactOpenRequest != null) aboutVisible = false
    }
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (aboutVisible) Modifier.clearAndSetSemantics {} else Modifier),
        ) {
            when {
                state.restoring -> LoadingScreen()
                !state.signedIn -> LoginScreen(
                    resetKey = loginResetKey,
                    loading = state.signingIn,
                    errorMessage = state.errorMessage,
                    internetAvailable = state.networkAvailable,
                    onSignIn = onSignIn,
                    onCheckServer = onCheckServer,
                )
                state.accountPage == AccountPage.Profile -> ProfileScreen(
                    accounts = state.accounts,
                    internetAvailable = state.networkAvailable,
                    onCheckServer = onCheckServerDetails,
                    onBack = onCloseProfile,
                    onAdd = onOpenAddAccount,
                    onRemoveAccount = onRemoveAccount,
                )
                state.accountPage == AccountPage.AddAccount -> AddAccountScreen(
                    resetKey = loginResetKey,
                    loading = state.addingAccount,
                    errorMessage = state.addAccountErrorMessage,
                    internetAvailable = state.networkAvailable,
                    onBack = onCloseAddAccount,
                    onAdd = onAddAccount,
                    onCheckServer = onCheckAddAccountServer,
                )
                !state.permissions.allRequiredGranted -> PermissionsScreen(
                    permissions = state.permissions,
                    multipleAccounts = state.accounts.size > 1,
                    onRequestNotifications = onRequestNotifications,
                    onRequestMicrophone = onRequestMicrophone,
                    onRequestFullScreenCalls = onRequestFullScreenCalls,
                    onRefresh = onRefreshPermissions,
                    onAbout = { aboutVisible = true },
                    onOpenProfile = onOpenProfile,
                )
                else -> HomeScreen(
                    state = state,
                    contactOpenRequest = contactOpenRequest,
                    onContactOpenRequestHandled = onContactOpenRequestHandled,
                    contactNameUpdate = contactNameUpdate,
                    ongoingCall = ongoingCall,
                    onCall = onCall,
                    onRenameContact = onRenameContact,
                    onRenameHandled = onRenameHandled,
                    onOpenCall = onOpenCall,
                    onContactsVisible = onContactsVisible,
                    onRefreshContacts = onRefreshContacts,
                    onContactsRefreshMessageHandled = onContactsRefreshMessageHandled,
                    onHistoryVisible = onHistoryVisible,
                    onLoadMoreHistory = onLoadMoreHistory,
                    onContactHistoryVisible = onContactHistoryVisible,
                    onContactHistoryHidden = onContactHistoryHidden,
                    onLoadMoreContactHistory = onLoadMoreContactHistory,
                    onRetryContactHistory = onRetryContactHistory,
                    contactPhotoEditorState = contactPhotoEditorState,
                    onContactPhotoTargetVisible = onContactPhotoTargetVisible,
                    onContactPhotoTargetHidden = onContactPhotoTargetHidden,
                    onChooseContactPhoto = onChooseContactPhoto,
                    onRemoveContactPhoto = onRemoveContactPhoto,
                    onCancelContactPhotoCrop = onCancelContactPhotoCrop,
                    onConfirmContactPhotoCrop = onConfirmContactPhotoCrop,
                    onContactPhotoMessageShown = onContactPhotoMessageShown,
                    onAbout = { aboutVisible = true },
                    onOpenProfile = onOpenProfile,
                )
            }
        }
        if (aboutVisible) {
            AboutScreen(
                serverUrl = state.serverUrl,
                internetAvailable = state.networkAvailable,
                onCheckServer = onCheckServerDetails,
                onBack = { aboutVisible = false },
            )
        }
        if (!state.networkAvailable) {
            OfflineBanner(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 64.dp, start = 16.dp, end = 16.dp),
            )
        }
    }
}

@Composable
private fun OfflineBanner(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth().semantics {
            contentDescription = "Нет подключения к интернету. Звонки и обновление данных недоступны"
        },
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        shadowElevation = 8.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_server_unavailable),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Нет подключения к интернету",
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Звонки и обновление данных недоступны",
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun LoadingScreen() {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AppMark(84.dp)
            Spacer(Modifier.height(24.dp))
            Text("TiniTalk", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(28.dp))
            CircularProgressIndicator(strokeWidth = 3.dp)
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun LoginScreen(
    resetKey: Int,
    loading: Boolean,
    errorMessage: String?,
    internetAvailable: Boolean,
    onSignIn: (String, String, String) -> Unit,
    onCheckServer: (String) -> ServerCheckResult,
) {
    val sharedKeyboardVisible = WindowInsets.isImeVisible
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color(0xFF111D30), MaterialTheme.colorScheme.background)),
            ).statusBarsPadding().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = if (sharedKeyboardVisible) 12.dp else 28.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(Modifier.fillMaxWidth().widthIn(max = 420.dp), horizontalAlignment = Alignment.Start) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppMark(52.dp)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("TiniTalk", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text("Звонки для своих", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(if (sharedKeyboardVisible) 16.dp else 28.dp))
                AccountCredentialsForm(
                    resetKey, loading, errorMessage, internetAvailable, "Войти", sharedKeyboardVisible,
                    onSignIn, onCheckServer,
                )
                Spacer(Modifier.height(6.dp))
                Text("v ${BuildConfig.COMMIT_HASH}", modifier = Modifier.align(Alignment.CenterHorizontally), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f), fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun PermissionsScreen(
    permissions: AppPermissionsState,
    multipleAccounts: Boolean,
    onRequestNotifications: () -> Unit,
    onRequestMicrophone: () -> Unit,
    onRequestFullScreenCalls: () -> Unit,
    onRefresh: () -> Unit,
    onAbout: () -> Unit,
    onOpenProfile: () -> Unit,
) {
    AppPage(multipleAccounts, onAbout, onOpenProfile) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(22.dp))
            Text("Разрешите звонки", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "Эти разрешения нужны, чтобы вы слышали собеседника и не пропускали входящие звонки.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            PermissionItem(
                title = "Уведомления",
                description = "Показывать входящие и активные звонки",
                granted = permissions.notificationsGranted,
                onRequest = onRequestNotifications,
            )
            PermissionItem(
                title = "Микрофон",
                description = "Передавать ваш голос во время разговора",
                granted = permissions.microphoneGranted,
                onRequest = onRequestMicrophone,
            )
            PermissionItem(
                title = "Полноэкранные оповещения",
                description = "Показывать звонок поверх экрана блокировки",
                granted = permissions.fullScreenIntentGranted,
                onRequest = onRequestFullScreenCalls,
            )
            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = onRefresh,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text("Проверить снова")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PermissionItem(
    title: String,
    description: String,
    granted: Boolean,
    onRequest: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(12.dp))
                if (granted) {
                    Text("Разрешено", color = CallAnswerGreen, fontWeight = FontWeight.SemiBold)
                } else {
                    FilledTonalButton(onClick = onRequest) { Text("Разрешить") }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                description,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HomeScreen(
    state: MainScreenState,
    contactOpenRequest: ContactOpenRequest?,
    onContactOpenRequestHandled: (ContactOpenRequest) -> Unit,
    contactNameUpdate: ContactNameUpdateState,
    ongoingCall: CallUiState?,
    onCall: (AccountContact) -> Unit,
    onRenameContact: (key: AccountPeerKey, customName: String?) -> Unit,
    onRenameHandled: () -> Unit,
    onOpenCall: () -> Unit,
    onContactsVisible: () -> Unit,
    onRefreshContacts: () -> Unit,
    onContactsRefreshMessageHandled: () -> Unit,
    onHistoryVisible: () -> Unit,
    onLoadMoreHistory: () -> Unit,
    onContactHistoryVisible: (AccountPeerKey) -> Unit,
    onContactHistoryHidden: () -> Unit,
    onLoadMoreContactHistory: () -> Unit,
    onRetryContactHistory: () -> Unit,
    contactPhotoEditorState: ContactPhotoEditorState,
    onContactPhotoTargetVisible: (ContactPhotoEditTarget) -> Unit,
    onContactPhotoTargetHidden: (ContactPhotoEditTarget) -> Unit,
    onChooseContactPhoto: (ContactPhotoEditTarget, ContactPhotoSource) -> Unit,
    onRemoveContactPhoto: (ContactPhotoEditTarget) -> Unit,
    onCancelContactPhotoCrop: () -> Unit,
    onConfirmContactPhotoCrop: (NormalizedCropSquare) -> Unit,
    onContactPhotoMessageShown: () -> Unit,
    onAbout: () -> Unit,
    onOpenProfile: () -> Unit,
) {
    val pagerState = rememberPagerState(pageCount = { 2 })
    val contactsListState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var selectedContactAccountId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedContactLogin by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedContactKey = selectedContactAccountId?.let { accountId ->
        selectedContactLogin?.let { login -> AccountPeerKey(AccountId(accountId), login) }
    }
    val visibleContacts = state.accountContacts
    val selectedAccountContact = visibleContacts.firstOrNull { it.peerKey == selectedContactKey }
    val selectedPhotoTarget = selectedAccountContact?.let { contact ->
        ContactPhotoEditTarget(
            accountId = contact.accountId,
            address = contact.address,
            displayName = contactDisplayName(contact.contact.displayName),
        )
    }
    val historyWindow = accountHistoryWindow(
        loaded = state.accountHistory,
        visibleLimit = state.historyVisibleLimit,
        cursors = state.historyNextBefores,
        unavailableAccounts = state.historyUnavailableAccounts,
    )
    val unavailableHistoryServers = state.accounts
        .filter { it.id in state.historyUnavailableAccounts }
        .map { serverAddress(it.serverUrl) }
        .distinct()

    LaunchedEffect(contactOpenRequest, visibleContacts, state.accounts) {
        val request = contactOpenRequest ?: return@LaunchedEffect
        if (state.accounts.none { it.id == request.peer.accountId }) {
            onContactOpenRequestHandled(request)
            return@LaunchedEffect
        }
        val requestedContact = visibleContacts.firstOrNull { it.peerKey == request.peer }
            ?: return@LaunchedEffect
        selectedPhotoTarget
            ?.takeIf { selectedContactKey != request.peer }
            ?.let(onContactPhotoTargetHidden)
        pagerState.scrollToPage(0)
        selectedContactAccountId = requestedContact.accountId.value
        selectedContactLogin = requestedContact.login
        onContactOpenRequestHandled(request)
    }

    BackHandler(
        enabled = shouldReturnToContactsOnBack(
            currentPage = pagerState.currentPage,
            contactOpen = selectedContactKey != null,
        ),
    ) {
        selectedPhotoTarget?.let(onContactPhotoTargetHidden)
        scope.launch { pagerState.animateScrollToPage(0) }
    }

    LaunchedEffect(selectedContactKey, visibleContacts) {
        val key = selectedContactKey ?: return@LaunchedEffect
        if (visibleContacts.none { it.peerKey == key }) {
            selectedContactAccountId = null
            selectedContactLogin = null
            scope.launch { snackbarHostState.showSnackbar("Контакт больше недоступен") }
        }
    }
    LaunchedEffect(selectedContactKey) {
        selectedContactKey?.let(onContactHistoryVisible) ?: onContactHistoryHidden()
    }
    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage == 1) onHistoryVisible() else onContactsVisible()
    }
    LaunchedEffect(state.contactsRefreshErrorMessage) {
        state.contactsRefreshErrorMessage?.let { message ->
            scope.launch { snackbarHostState.showSnackbar(message) }
            onContactsRefreshMessageHandled()
        }
    }
    LaunchedEffect(selectedPhotoTarget) {
        selectedPhotoTarget?.let(onContactPhotoTargetVisible)
    }
    LaunchedEffect(contactPhotoEditorState.message) {
        contactPhotoEditorState.message?.let { message ->
            scope.launch { snackbarHostState.showSnackbar(message) }
            onContactPhotoMessageShown()
        }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = if (selectedAccountContact == null) Modifier else Modifier.clearAndSetSemantics {},
        ) {
            AppPage(state.accounts.size > 1, onAbout, onOpenProfile) {
                Column(modifier = Modifier.fillMaxSize()) {
                    if (ongoingCall != null) {
                        OngoingCallBanner(ongoingCall, onOpenCall)
                    }
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    ) { page ->
                        if (page == 0) {
                            ContactsPage(
                                contacts = visibleContacts,
                                latestUnreadMissedByContact = state.latestUnreadMissedByAccountContact,
                                internetAvailable = state.networkAvailable,
                                listState = contactsListState,
                                refreshing = state.contactsRefreshing,
                                onRefresh = onRefreshContacts,
                                onContactSelected = {
                                    selectedContactAccountId = it.accountId.value
                                    selectedContactLogin = it.login
                                },
                            )
                        } else {
                            HistoryScreen(
                                items = historyWindow.items,
                                itemKeys = historyWindow.items.map { accountScopedKey(it.accountId, it.id.toString()) },
                                internetAvailable = state.networkAvailable,
                                loaded = state.historyLoaded,
                                loading = state.historyLoading,
                                loadingMore = state.historyLoadingMore,
                                hasMore = historyWindow.hasMore,
                                errorMessage = state.historyErrorMessage,
                                unavailableServers = unavailableHistoryServers,
                                onLoadMore = onLoadMoreHistory,
                                onRefresh = onHistoryVisible,
                            )
                        }
                    }
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        NavigationBarItem(
                            selected = pagerState.currentPage == 0,
                            onClick = { scope.launch { pagerState.animateScrollToPage(0) } },
                            icon = {
                                Icon(
                                    painter = painterResource(R.drawable.ic_contacts),
                                    contentDescription = null,
                                    modifier = Modifier.size(28.dp),
                                )
                            },
                            label = { Text("Контакты") },
                        )
                        NavigationBarItem(
                            selected = pagerState.currentPage == 1,
                            onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
                            modifier = Modifier.semantics {
                                contentDescription = historyTabDescription(state.unreadMissedCount)
                            },
                            icon = {
                                Icon(
                                    painter = painterResource(R.drawable.ic_history),
                                    contentDescription = null,
                                    modifier = Modifier.size(28.dp),
                                )
                            },
                            label = {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text("История", maxLines = 1)
                                    historyBadgeText(state.unreadMissedCount)?.let { count ->
                                        Badge(
                                            modifier = Modifier.clearAndSetSemantics { },
                                            containerColor = CallRejectRed,
                                            contentColor = Color.White,
                                        ) {
                                            Text(
                                                text = count,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                            )
                                        }
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
        selectedAccountContact?.let { contact ->
            ContactScreen(
                contact = contact.contact,
                contactAddress = contact.address,
                identityKey = accountScopedKey(contact.accountId, contact.login),
                accountServerUrl = contact.serverUrl.takeIf {
                    state.accounts
                        .map { normalizeServerUrl(it.serverUrl).lowercase(Locale.ROOT) }
                        .distinct()
                        .size > 1
                },
                internetAvailable = state.networkAvailable,
                nameUpdate = contactNameUpdate.takeIf { it.key == contact.peerKey }
                    ?: ContactNameUpdateState(),
                history = state.contactHistory,
                ongoingCall = ongoingCall,
                onBack = {
                    selectedPhotoTarget?.let(onContactPhotoTargetHidden)
                    selectedContactAccountId = null
                    selectedContactLogin = null
                },
                onCall = { onCall(contact) },
                onOpenCall = onOpenCall,
                onRename = { customName -> onRenameContact(contact.peerKey, customName) },
                onRenameHandled = onRenameHandled,
                onLoadMoreHistory = onLoadMoreContactHistory,
                onRetryHistory = onRetryContactHistory,
                photoTarget = selectedPhotoTarget,
                photoState = contactPhotoEditorState.takeIf { it.target == selectedPhotoTarget }
                    ?: ContactPhotoEditorState(target = selectedPhotoTarget),
                onChoosePhotoSource = onChooseContactPhoto,
                onRemovePhoto = onRemoveContactPhoto,
            )
        }
        if (
            contactPhotoEditorState.phase == ContactPhotoEditorPhase.Cropping ||
            contactPhotoEditorState.phase == ContactPhotoEditorPhase.Saving
        ) {
            ContactPhotoCropOverlay(
                state = contactPhotoEditorState,
                onCancel = onCancelContactPhotoCrop,
                onDone = onConfirmContactPhotoCrop,
            )
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContactsPage(
    contacts: List<AccountContact>,
    latestUnreadMissedByContact: Map<AccountPeerKey, Long>,
    internetAvailable: Boolean,
    listState: LazyListState,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onContactSelected: (AccountContact) -> Unit,
) {
    val contactsWithServerSubtitle = remember(contacts) { contactsRequiringServerSubtitle(contacts) }
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = { if (internetAvailable) onRefresh() },
        modifier = Modifier.fillMaxSize(),
    ) {
        if (contacts.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    if (internetAvailable) "Контактов пока нет" else "Нет подключения к интернету",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (internetAvailable) {
                        "Добавьте абонентов в настройках сервера."
                    } else {
                        "Контакты появятся после восстановления связи."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(contacts, key = { contact -> accountScopedKey(contact.accountId, contact.login) }) { contact ->
                    ContactRow(
                        contact,
                        serverHostname = if (contact.peerKey in contactsWithServerSubtitle) {
                            serverHostname(contact.serverUrl)
                        } else {
                            null
                        },
                        latestUnreadMissedAt = latestUnreadMissedByContact[contact.peerKey],
                    ) { onContactSelected(contact) }
                }
            }
        }
    }
}

@Composable
private fun OngoingCallBanner(state: CallUiState, onOpen: () -> Unit) {
    val status = when {
        state.connectionHealth == ConnectionHealth.Reconnecting -> "Восстанавливаем связь…"
        state.connectionHealth == ConnectionHealth.Poor -> "Слабая сеть"
        state.phase == CallPhase.Active && state.connectionHealth == ConnectionHealth.Connecting -> "Соединяемся…"
        state.phase == CallPhase.Active -> "Идёт разговор"
        state.direction == CallDirection.Incoming -> "Входящий звонок"
        state.phase == CallPhase.Ringing -> "Ждём ответа…"
        else -> "Пробуем связаться…"
    }
    Surface(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ContactAvatar(
                address = state.peer?.contactAddress,
                displayName = state.peer?.displayName?.ifBlank { "TiniTalk" } ?: "TiniTalk",
                fallbackLogin = state.peer?.login ?: state.peer?.displayName ?: "TiniTalk",
                size = 44.dp,
                borderWidth = 0.dp,
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.peer?.displayName?.ifBlank { "TiniTalk" } ?: "TiniTalk",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
                )
            }
            Text(
                text = "Открыть",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun ContactRow(
    contact: AccountContact,
    serverHostname: String?,
    latestUnreadMissedAt: Long?,
    onOpen: (AccountContact) -> Unit,
) {
    val name = contactDisplayName(contact.displayName)
    val missedSubtitle = latestUnreadMissedAt?.let(::missedContactSubtitle)
    Surface(
        onClick = { onOpen(contact) },
        modifier = Modifier.fillMaxWidth().semantics {
            contentDescription = listOfNotNull("Открыть контакт: $name", serverHostname, missedSubtitle).joinToString(". ")
        },
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.78f)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 82.dp).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ContactAvatar(
                address = contact.address,
                displayName = name,
                fallbackLogin = contact.login,
                size = 52.dp,
                borderWidth = 1.dp,
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (serverHostname != null) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        serverHostname,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (missedSubtitle != null) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        missedSubtitle,
                        color = CallRejectRed.copy(alpha = 0.9f),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_chevron_right),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(23.dp),
                )
            }
        }
    }
}

@Composable
private fun AppPage(
    multipleAccounts: Boolean,
    onAbout: () -> Unit,
    onOpenProfile: () -> Unit,
    content: @Composable () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().height(68.dp).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CompositionLocalProvider(LocalRippleConfiguration provides null) {
                    Row(
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .clickable(onClick = onAbout)
                            .semantics { contentDescription = "О программе" },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppMark(42.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("TiniTalk", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onOpenProfile) {
                    Icon(
                        painter = painterResource(
                            if (multipleAccounts) R.drawable.ic_contacts else R.drawable.ic_person,
                        ),
                        contentDescription = "Профиль",
                        modifier = Modifier.size(26.dp),
                        tint = Color.White,
                    )
                }
            }
            Box(modifier = Modifier.weight(1f)) { content() }
        }
    }
}

@Composable
internal fun AppMark(size: androidx.compose.ui.unit.Dp) {
    Surface(
        modifier = Modifier.size(size),
        shape = RoundedCornerShape(size / 3f),
        color = BrandBackground,
        border = BorderStroke(1.dp, BrandGold.copy(alpha = 0.55f)),
        shadowElevation = 2.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(R.drawable.ic_call),
                contentDescription = null,
                tint = BrandGold,
                modifier = Modifier.size(size * 0.46f),
            )
        }
    }
}
