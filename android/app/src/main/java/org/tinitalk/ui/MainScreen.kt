package org.tinitalk.ui

import org.tinitalk.i18n.appString

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.material3.BadgedBox
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
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
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
import org.tinitalk.data.FavoriteContactsStore
import android.content.SharedPreferences
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
        ServerCheckPresentation(ServerCheckIndicator.Unavailable, appString(R.string.text_no_internet_connection_3))
    !serverReady -> ServerCheckPresentation(ServerCheckIndicator.Unavailable, appString(R.string.text_enter_a_server_address_281))
    checking -> ServerCheckPresentation(ServerCheckIndicator.Checking, appString(R.string.text_checking_connection_282))
    result == null -> ServerCheckPresentation(ServerCheckIndicator.Checking, appString(R.string.text_checking_connection_282))
    result == ServerCheckResult.Available ->
        ServerCheckPresentation(ServerCheckIndicator.Available, appString(R.string.text_tinitalk_server_available_283))
    result == ServerCheckResult.WrongServer ->
        ServerCheckPresentation(ServerCheckIndicator.Unavailable, appString(R.string.text_no_tinitalk_server_at_this_address_284))
    result == ServerCheckResult.ServerOutdated ->
        ServerCheckPresentation(ServerCheckIndicator.Incompatible, appString(R.string.text_the_server_is_incompatible_with_this_app_version_18))
    result == ServerCheckResult.AppOutdated ->
        ServerCheckPresentation(ServerCheckIndicator.Incompatible, appString(R.string.text_tinitalk_is_out_of_date_install_the_latest_version_19))
    else -> ServerCheckPresentation(
        ServerCheckIndicator.Unavailable,
        appString(R.string.text_server_unavailable_check_the_address_and_your_connection_285),
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
    val passwordSetupRequired: Boolean = false,
    val loginRetryAtMillis: Long = 0,
    val networkAvailable: Boolean = true,
    val accountPage: AccountPage = AccountPage.Main,
    val accounts: List<AccountSummary> = emptyList(),
    val addingAccount: Boolean = false,
    val addAccountErrorMessage: String? = null,
    val addAccountPasswordSetupRequired: Boolean = false,
    val addAccountRetryAtMillis: Long = 0,
    val passwordChanging: Boolean = false,
    val passwordChangeErrorMessage: String? = null,
    val passwordChangeCompletionKey: Int = 0,
    val passwordRetryAtMillis: Long = 0,
    val signInRecovery: AccountSummary? = null,
    val addingContact: Boolean = false,
    val addContactErrorMessage: String? = null,
    val removingContact: AccountPeerKey? = null,
    val removeContactErrorFor: AccountPeerKey? = null,
    val removeContactErrorMessage: String? = null,
)

enum class AccountPage { Main, Profile, AddAccount, AddContact }

data class AccountSummary(
    val id: AccountId,
    val serverUrl: String,
    val login: String,
    val displayName: String?,
    val passwordSet: Boolean? = null,
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
    val names = Collator.getInstance(org.tinitalk.i18n.AppLanguage.locale).apply { strength = Collator.PRIMARY }
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
    onSetInitialPassword: (url: String, login: String, temporaryPassword: String, newPassword: String) -> Unit =
        { _, _, _, _ -> },
    onCheckServer: (url: String) -> ServerCheckResult,
    onCheckServerDetails: (url: String) -> ServerCheckDetails,
    onCheckPasswordSet: (AccountId) -> Boolean? = { null },
    onRequestNotifications: () -> Unit,
    onRequestMicrophone: () -> Unit,
    onRequestFullScreenCalls: () -> Unit,
    onRefreshPermissions: () -> Unit,
    onCall: (AccountContact) -> Unit,
    onPinContact: (AccountContact) -> Unit = {},
    pinnedContacts: Set<AccountPeerKey>? = null,
    onRefreshShortcuts: () -> Unit = {},
    onRenameContact: (key: AccountPeerKey, customName: String) -> Unit,
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
    onSetInitialPasswordForAccount: (url: String, login: String, temporaryPassword: String, newPassword: String) -> Unit =
        { _, _, _, _ -> },
    onRemoveAccount: (AccountId) -> Unit,
    onChangePassword: (AccountId, String, String) -> Unit = { _, _, _ -> },
    onCheckAddAccountServer: (String) -> ServerCheckResult = onCheckServer,
    onOpenAddContact: () -> Unit = {},
    onCloseAddContact: () -> Unit = {},
    onAddContactInputChanged: () -> Unit = {},
    onAddContact: (AccountId, String, String) -> Unit = { _, _, _ -> },
    onRemoveContact: (AccountContact) -> Unit = {},
    onRemoveContactDismissed: () -> Unit = {},
    onCancelPasswordSetup: () -> Unit = {},
    onCancelAccountPasswordSetup: () -> Unit = {},
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
                    passwordSetupRequired = state.passwordSetupRequired,
                    onSignIn = onSignIn,
                    signInRecovery = state.signInRecovery,
                    onSetPassword = onSetInitialPassword,
                    onCancelPasswordSetup = onCancelPasswordSetup,
                    retryAtMillis = state.loginRetryAtMillis,
                    onCheckServer = onCheckServer,
                )
                state.accountPage == AccountPage.Profile -> ProfileScreen(
                    accounts = state.accounts,
                    internetAvailable = state.networkAvailable,
                    onCheckServer = onCheckServerDetails,
                    onCheckPasswordSet = onCheckPasswordSet,
                    onBack = onCloseProfile,
                    onAdd = onOpenAddAccount,
                    onRemoveAccount = onRemoveAccount,
                    passwordChanging = state.passwordChanging,
                    passwordErrorMessage = state.passwordChangeErrorMessage,
                    passwordChangeCompletionKey = state.passwordChangeCompletionKey,
                    onChangePassword = onChangePassword,
                    retryAtMillis = state.passwordRetryAtMillis,
                )
                state.accountPage == AccountPage.AddAccount -> AddAccountScreen(
                    resetKey = loginResetKey,
                    loading = state.addingAccount,
                    errorMessage = state.addAccountErrorMessage,
                    internetAvailable = state.networkAvailable,
                    passwordSetupRequired = state.addAccountPasswordSetupRequired,
                    onBack = onCloseAddAccount,
                    onAdd = onAddAccount,
                    onSetPassword = onSetInitialPasswordForAccount,
                    onCancelPasswordSetup = onCancelAccountPasswordSetup,
                    retryAtMillis = state.addAccountRetryAtMillis,
                    onCheckServer = onCheckAddAccountServer,
                    signInRecovery = state.signInRecovery,
                )
                state.accountPage == AccountPage.AddContact -> AddContactScreen(
                    accounts = state.accounts,
                    loading = state.addingContact,
                    errorMessage = state.addContactErrorMessage,
                    internetAvailable = state.networkAvailable,
                    onBack = onCloseAddContact,
                    onInputChanged = onAddContactInputChanged,
                    onAdd = onAddContact,
                )
                !state.permissions.allRequiredGranted -> PermissionsScreen(
                    permissions = state.permissions,
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
                    onPinContact = onPinContact,
                    pinnedContacts = pinnedContacts,
                    onRefreshShortcuts = onRefreshShortcuts,
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
                    onOpenAddContact = onOpenAddContact,
                    onRemoveContact = onRemoveContact,
                    onRemoveContactDismissed = onRemoveContactDismissed,
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
            contentDescription = appString(R.string.text_no_internet_connection_calls_and_updates_are_unavailable_286)
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
                    appString(R.string.text_no_internet_connection_3),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    appString(R.string.text_calls_and_updates_are_unavailable_287),
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
    passwordSetupRequired: Boolean,
    onSignIn: (String, String, String) -> Unit,
    onSetPassword: (String, String, String, String) -> Unit,
    onCancelPasswordSetup: () -> Unit,
    retryAtMillis: Long,
    onCheckServer: (String) -> ServerCheckResult,
    signInRecovery: AccountSummary? = null,
) {
    val credentials = rememberAccountCredentials(resetKey, signInRecovery?.login.orEmpty(), signInRecovery?.serverUrl.orEmpty())
    if (passwordSetupRequired) {
        PasswordSetupScreen(credentials, loading, errorMessage, internetAvailable, retryAtMillis, onSetPassword, onCancelPasswordSetup)
        return
    }
    val sharedKeyboardVisible = WindowInsets.isImeVisible
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color(0xFF111D30), MaterialTheme.colorScheme.background)),
            ).statusBarsPadding().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = if (sharedKeyboardVisible) 12.dp else 28.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(Modifier.widthIn(max = 420.dp).fillMaxWidth(), horizontalAlignment = Alignment.Start) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppMark(52.dp)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("TiniTalk", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text(appString(R.string.text_calls_for_your_circle_288), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(if (compactLandscape()) 8.dp else if (sharedKeyboardVisible) 16.dp else 28.dp))
                AccountCredentialsForm(
                    credentials, loading, errorMessage, internetAvailable, appString(R.string.text_sign_in_115), sharedKeyboardVisible,
                    onSignIn, onCheckServer, retryAtMillis,
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
    onRequestNotifications: () -> Unit,
    onRequestMicrophone: () -> Unit,
    onRequestFullScreenCalls: () -> Unit,
    onRefresh: () -> Unit,
    onAbout: () -> Unit,
    onOpenProfile: () -> Unit,
) {
    AppPage(onAbout, onOpenProfile) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(22.dp))
            Text(appString(R.string.text_enable_calling_289), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                appString(R.string.text_these_permissions_let_you_hear_the_other_person_and_receive_incom_290),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            PermissionItem(
                title = appString(R.string.text_notifications_291),
                description = appString(R.string.text_show_incoming_and_active_calls_292),
                granted = permissions.notificationsGranted,
                onRequest = onRequestNotifications,
            )
            PermissionItem(
                title = appString(R.string.text_microphone_178),
                description = appString(R.string.text_transmit_your_voice_during_calls_293),
                granted = permissions.microphoneGranted,
                onRequest = onRequestMicrophone,
            )
            PermissionItem(
                title = appString(R.string.text_full_screen_alerts_294),
                description = appString(R.string.text_show_calls_over_the_lock_screen_295),
                granted = permissions.fullScreenIntentGranted,
                onRequest = onRequestFullScreenCalls,
            )
            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = onRefresh,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text(appString(R.string.text_check_again_296))
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
                    Text(appString(R.string.text_allowed_297), color = CallAnswerGreen, fontWeight = FontWeight.SemiBold)
                } else {
                    FilledTonalButton(onClick = onRequest) { Text(appString(R.string.text_allow_60)) }
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
    onPinContact: (AccountContact) -> Unit,
    pinnedContacts: Set<AccountPeerKey>?,
    onRefreshShortcuts: () -> Unit,
    onRenameContact: (key: AccountPeerKey, customName: String) -> Unit,
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
    onOpenAddContact: () -> Unit,
    onRemoveContact: (AccountContact) -> Unit,
    onRemoveContactDismissed: () -> Unit,
    onAbout: () -> Unit,
    onOpenProfile: () -> Unit,
) {
    val pagerState = rememberPagerState(pageCount = { 2 })
    val contactsListState = rememberLazyListState()
    val favoritesListState = rememberLazyListState()
    val context = LocalContext.current
    val favoritesStore = remember(context) { FavoriteContactsStore(context) }
    var favoriteKeys by remember(favoritesStore) { mutableStateOf(favoritesStore.load()) }
    DisposableEffect(favoritesStore) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            favoriteKeys = favoritesStore.load()
        }
        favoritesStore.preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { favoritesStore.preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val scope = rememberCoroutineScope()
    var selectedContactAccountId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedContactLogin by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedContactKey = selectedContactAccountId?.let { accountId ->
        selectedContactLogin?.let { login -> AccountPeerKey(AccountId(accountId), login) }
    }
    val snackbarHostState = remember(selectedContactKey) { SnackbarHostState() }
    val snackbarScope = androidx.compose.runtime.key(selectedContactKey) { rememberCoroutineScope() }
    val visibleContacts = state.accountContacts
    val latestVisibleContacts by androidx.compose.runtime.rememberUpdatedState(visibleContacts)
    val favoriteContacts = remember(favoriteKeys, visibleContacts) {
        val byKey = visibleContacts.associateBy { it.peerKey }
        favoriteKeys.mapNotNull(byKey::get)
    }
    val contactsPagerState = rememberPagerState(pageCount = { if (favoriteContacts.isNotEmpty()) 2 else 1 })
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
            snackbarScope.launch { snackbarHostState.showSnackbar(message) }
            onContactsRefreshMessageHandled()
        }
    }
    LaunchedEffect(selectedPhotoTarget) {
        selectedPhotoTarget?.let(onContactPhotoTargetVisible)
    }
    LaunchedEffect(contactPhotoEditorState.message) {
        contactPhotoEditorState.message?.let { message ->
            snackbarScope.launch { snackbarHostState.showSnackbar(message) }
            onContactPhotoMessageShown()
        }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = if (selectedAccountContact == null) Modifier else Modifier.clearAndSetSemantics {},
        ) {
            AppPage(onAbout, onOpenProfile, showHeader = !compactLandscape(), hasNavigation = true) {
                val landscape = compactLandscape()
                val navigationDividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
                Row(Modifier.fillMaxSize()) {
                    if (landscape) NavigationRail(
                        modifier = Modifier.leftNavigationBarBackdrop().drawWithContent {
                            drawContent()
                            val thickness = 1.dp.toPx()
                            val x = if (layoutDirection == LayoutDirection.Ltr) size.width - thickness / 2 else thickness / 2
                            drawLine(navigationDividerColor, Offset(x, 0f), Offset(x, size.height), thickness)
                        },
                        containerColor = MaterialTheme.colorScheme.surface,
                        windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start),
                    ) {
                        CompositionLocalProvider(LocalRippleConfiguration provides null) {
                            Box(Modifier.size(56.dp).clickable(onClick = onAbout)
                                .semantics { contentDescription = appString(R.string.text_about_102) },
                                contentAlignment = Alignment.Center) {
                                AppMark(56.dp)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        NavigationRailItem(selected = pagerState.currentPage == 0,
                            onClick = { scope.launch { pagerState.animateScrollToPage(0) } },
                            icon = { Icon(painterResource(R.drawable.ic_contacts), null) },
                            label = { Text(appString(R.string.text_contacts_298)) })
                        NavigationRailItem(selected = pagerState.currentPage == 1,
                            onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
                            modifier = Modifier.semantics { contentDescription = historyTabDescription(state.unreadMissedCount) },
                            icon = {
                                BadgedBox(badge = {
                                    historyBadgeText(state.unreadMissedCount)?.let { count ->
                                        Badge(
                                            modifier = Modifier.clearAndSetSemantics { },
                                            containerColor = CallRejectRed,
                                            contentColor = Color.White,
                                        ) {
                                            Text(count, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                                maxLines = 1, softWrap = false)
                                        }
                                    }
                                }) {
                                    Icon(painterResource(R.drawable.ic_history), null)
                                }
                            },
                            label = { Text(appString(R.string.text_history_251), maxLines = 1) })
                        Spacer(Modifier.weight(1f))
                        NavigationRailItem(selected = false, onClick = onOpenProfile,
                            modifier = Modifier.semantics { contentDescription = appString(R.string.text_profile_308) },
                            icon = {
                                Icon(painterResource(R.drawable.ic_profile), contentDescription = null,
                                    modifier = Modifier.size(32.dp).testTag("profile-icon"))
                            },
                            label = { Text(appString(R.string.text_profile_308)) })
                    }
                Column(modifier = Modifier.fillMaxSize().then(if (landscape)
                    Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.End))
                    else Modifier), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (ongoingCall != null) {
                        OngoingCallBanner(ongoingCall, onOpenCall)
                    }
                    HorizontalPager(
                        state = pagerState,
                        userScrollEnabled = false,
                        modifier = Modifier.weight(1f).fillMaxWidth().testTag("main-pager"),
                    ) { page ->
                        Box(Modifier.fillMaxSize().testTag("main-page-$page"), contentAlignment = Alignment.TopCenter) {
                            if (page == 0) {
                                FavoriteContactsPager(contactsPagerState, favoriteContacts.isNotEmpty()) { favorites ->
                                    ContactsPage(
                                        contacts = if (favorites) favoriteContacts else visibleContacts,
                                        favoriteKeys = favoriteKeys,
                                        latestUnreadMissedByContact = state.latestUnreadMissedByAccountContact,
                                        internetAvailable = state.networkAvailable,
                                        listState = if (favorites) favoritesListState else contactsListState,
                                        refreshing = state.contactsRefreshing,
                                        onRefresh = onRefreshContacts,
                                        onAddContact = onOpenAddContact,
                                        onReorder = if (favorites) favoritesStore::reorder else null,
                                        onContactSelected = {
                                            selectedContactAccountId = it.accountId.value
                                            selectedContactLogin = it.login
                                        },
                                    )
                                }
                            } else {
                                Box(Modifier.then(if (landscape) Modifier.widthIn(max = 600.dp) else Modifier)
                                    .fillMaxSize().testTag("main-page-content-$page")) {
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
                                        onContactSelected = { peer ->
                                            val contact = visibleContacts.firstOrNull { it.peerKey == peer }
                                            if (contact == null) {
                                                snackbarScope.launch {
                                                    snackbarHostState.showSnackbar(appString(R.string.text_contact_no_longer_available_211))
                                                }
                                            } else {
                                                selectedContactAccountId = contact.accountId.value
                                                selectedContactLogin = contact.login
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                    if (!landscape) NavigationBar(
                        modifier = Modifier.drawWithContent {
                            drawContent()
                            val thickness = 1.dp.toPx()
                            drawLine(navigationDividerColor, Offset(0f, thickness / 2), Offset(size.width, thickness / 2), thickness)
                        },
                        containerColor = MaterialTheme.colorScheme.surface,
                    ) {
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
                            label = { Text(appString(R.string.text_contacts_298)) },
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
                                    Text(appString(R.string.text_history_251), maxLines = 1)
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
        }
        selectedAccountContact?.let { contact ->
            ContactScreen(
                contact = contact.contact,
                favorite = contact.peerKey in favoriteKeys,
                onToggleFavorite = {
                    val position = favoriteKeys.indexOf(contact.peerKey)
                    val adding = position < 0
                    favoritesStore.setFavorite(contact.peerKey, adding)
                    favoriteKeys = favoritesStore.load()
                    if (adding && favoriteKeys.size == 1) scope.launch { contactsPagerState.scrollToPage(0) }
                    if (!adding) snackbarScope.launch {
                        snackbarHostState.currentSnackbarData?.dismiss()
                        val result = snackbarHostState.showSnackbar(appString(R.string.text_removed_from_favorites_299), actionLabel = appString(R.string.text_undo_196))
                        if (result == androidx.compose.material3.SnackbarResult.ActionPerformed &&
                            latestVisibleContacts.any { it.peerKey == contact.peerKey } &&
                            favoritesStore.load().none { it == contact.peerKey }) {
                            favoritesStore.setFavorite(contact.peerKey, true, position)
                        }
                    }
                },
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
                onPinContact = { onPinContact(contact) },
                shortcutPinned = pinnedContacts?.contains(contact.peerKey),
                onRefreshShortcuts = onRefreshShortcuts,
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
                removing = state.removingContact == contact.peerKey,
                removeErrorMessage = state.removeContactErrorMessage.takeIf {
                    state.removeContactErrorFor == contact.peerKey
                },
                onRemoveContact = { onRemoveContact(contact) },
                onRemoveContactDismissed = onRemoveContactDismissed,
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
        androidx.compose.runtime.key(snackbarHostState) {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp),
            ) { data ->
                ContactSnackbar(data)
            }
        }
    }
}

@Composable
private fun ContactSnackbar(data: SnackbarData) {
    val remaining = remember(data) { Animatable(1f) }
    val accent = MaterialTheme.colorScheme.primary
    LaunchedEffect(data) {
        // This is a countdown: system animation speed must not change its duration.
        withContext(object : MotionDurationScale { override val scaleFactor = 1f }) {
            remaining.animateTo(0f, tween(3_000, easing = LinearEasing))
        }
        data.dismiss()
    }
    Snackbar(
        snackbarData = data,
        modifier = Modifier.clip(CircleShape)
            .drawWithContent {
                drawContent()
                val start = 28.dp.toPx()
                val end = size.width - start
                val y = size.height - 5.dp.toPx()
                if (remaining.value > 0f) {
                    drawLine(accent.copy(alpha = 0.8f), Offset(start, y),
                        Offset(start + (end - start) * remaining.value, y), 2.dp.toPx(), StrokeCap.Round)
                }
            },
        shape = CircleShape,
        containerColor = Color(0xFF45474C).copy(alpha = 0.88f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        actionColor = accent,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContactsPage(
    contacts: List<AccountContact>,
    favoriteKeys: List<AccountPeerKey>,
    latestUnreadMissedByContact: Map<AccountPeerKey, Long>,
    internetAvailable: Boolean,
    listState: LazyListState,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onAddContact: () -> Unit,
    onContactSelected: (AccountContact) -> Unit,
    onReorder: ((List<AccountPeerKey>) -> Unit)? = null,
) {
    val contactsWithServerSubtitle = remember(contacts) { contactsRequiringServerSubtitle(contacts) }
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = { if (internetAvailable) onRefresh() },
        modifier = Modifier.fillMaxSize(),
    ) {
        if (contacts.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        if (internetAvailable) appString(R.string.text_no_contacts_yet_300) else appString(R.string.text_no_internet_connection_3),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (internetAvailable) {
                            appString(R.string.text_add_your_first_contact_301)
                        } else {
                            appString(R.string.text_contacts_will_appear_when_the_connection_is_restored_302)
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AddListButton(
                    onClick = onAddContact,
                    enabled = internetAvailable,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp),
                )
            }
        } else if (onReorder != null) {
            ReorderableFavoriteContacts(
                contacts = contacts,
                listState = listState,
                onReorder = onReorder,
            ) { contact, rowModifier ->
                ContactRow(
                    contact = contact,
                    serverHostname = if (contact.peerKey in contactsWithServerSubtitle) serverHostname(contact.serverUrl) else null,
                    latestUnreadMissedAt = latestUnreadMissedByContact[contact.peerKey],
                    onOpen = onContactSelected,
                    modifier = rowModifier,
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
                        showFavoriteBadge = contact.peerKey in favoriteKeys,
                        serverHostname = if (contact.peerKey in contactsWithServerSubtitle) {
                            serverHostname(contact.serverUrl)
                        } else {
                            null
                        },
                        latestUnreadMissedAt = latestUnreadMissedByContact[contact.peerKey],
                    ) { onContactSelected(contact) }
                }
                item(key = "add-contact") {
                    AddListButton(
                        onClick = onAddContact,
                        enabled = internetAvailable,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
internal fun AddListButton(
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp),
        colors = ButtonDefaults.textButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
        ),
    ) {
        Text(appString(R.string.text_add_303), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun OngoingCallBanner(state: CallUiState, onOpen: () -> Unit) {
    val status = when {
        state.connectionHealth == ConnectionHealth.Reconnecting -> appString(R.string.text_reconnecting_131)
        state.connectionHealth == ConnectionHealth.Poor -> appString(R.string.text_weak_connection_132)
        state.phase == CallPhase.Active && state.connectionHealth == ConnectionHealth.Connecting -> appString(R.string.text_connecting_130)
        state.phase == CallPhase.Active -> appString(R.string.text_in_a_call_133)
        state.direction == CallDirection.Incoming -> appString(R.string.text_incoming_call_62)
        state.phase == CallPhase.Ringing -> appString(R.string.text_waiting_for_an_answer_4)
        else -> appString(R.string.text_trying_to_connect_5)
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
                text = appString(R.string.text_open_304),
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
    modifier: Modifier = Modifier,
    showFavoriteBadge: Boolean = false,
    onOpen: (AccountContact) -> Unit,
) {
    val name = contactDisplayName(contact.displayName)
    val missedSubtitle = latestUnreadMissedAt?.let(::missedContactSubtitle)
    val detailsSubtitle = listOfNotNull(
        serverHostname,
        appString(R.string.text_calls_not_available_yet_305).takeIf { !contact.canCall },
    ).joinToString(" • ").takeIf(String::isNotEmpty)
    val rowHeight = if (compactLandscape()) 64.dp else 82.dp
    val avatarInset = 4.dp
    val avatarSize = rowHeight - avatarInset * 2
    Surface(
        onClick = { onOpen(contact) },
        modifier = modifier.fillMaxWidth().semantics {
            contentDescription = listOfNotNull(appString(R.string.text_open_contact_value_306, name), appString(R.string.text_in_favorites_307).takeIf { showFavoriteBadge }, detailsSubtitle, missedSubtitle)
                .joinToString(". ")
        },
        shape = RoundedCornerShape(rowHeight / 2),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.78f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(rowHeight)
                .padding(start = avatarInset, top = avatarInset, end = 14.dp, bottom = avatarInset),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(avatarSize)) {
                ContactAvatar(
                    address = contact.address,
                    displayName = name,
                    fallbackLogin = contact.login,
                    size = avatarSize,
                    borderWidth = 1.dp,
                )
                if (showFavoriteBadge) {
                    Surface(
                        modifier = Modifier.align(Alignment.BottomEnd).size(18.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surface,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                painter = painterResource(R.drawable.ic_star),
                                contentDescription = null,
                                tint = BrandGold,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (detailsSubtitle != null) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        detailsSubtitle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                            alpha = if (contact.canCall) 1f else 0.78f,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (contact.canCall) FontWeight.Normal else FontWeight.Medium,
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
    onAbout: () -> Unit,
    onOpenProfile: () -> Unit,
    showHeader: Boolean = true,
    hasNavigation: Boolean = false,
    content: @Composable () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        // Navigation components paint to the edge and apply their own content insets.
        val pageInsets = when {
            hasNavigation && compactLandscape() -> Modifier
            hasNavigation -> Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            else -> Modifier.safeDrawingPadding()
        }
        Column(modifier = Modifier.fillMaxSize().then(pageInsets)) {
            if (showHeader) Row(
                modifier = Modifier.fillMaxWidth().height(if (compactLandscape()) 48.dp else 68.dp).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CompositionLocalProvider(LocalRippleConfiguration provides null) {
                    Row(
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .clickable(onClick = onAbout)
                            .semantics { contentDescription = appString(R.string.text_about_102) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppMark(if (compactLandscape()) 32.dp else 42.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("TiniTalk", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onOpenProfile) {
                    Icon(
                        painter = painterResource(R.drawable.ic_profile),
                        contentDescription = appString(R.string.text_profile_308),
                        modifier = Modifier.size(32.dp).testTag("profile-icon"),
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
