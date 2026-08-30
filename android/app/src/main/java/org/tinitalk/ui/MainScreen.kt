package org.tinitalk.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
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
import org.tinitalk.R
import org.tinitalk.call.CallDirection
import org.tinitalk.call.CallPhase
import org.tinitalk.call.CallUiState
import org.tinitalk.call.ConnectionHealth
import org.tinitalk.data.CallHistoryItem
import org.tinitalk.data.CallUnreadState
import org.tinitalk.data.Contact
import org.tinitalk.data.ContactPage
import org.tinitalk.data.ServerCheckDetails
import org.tinitalk.data.ServerCheckResult
import org.tinitalk.permissions.AppPermissionsState
import org.tinitalk.ui.theme.BrandBackground
import org.tinitalk.ui.theme.BrandGold
import org.tinitalk.ui.theme.CallAnswerGreen
import org.tinitalk.ui.theme.CallRejectRed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    !serverReady -> ServerCheckPresentation(ServerCheckIndicator.Unavailable, "Введите полный адрес сервера")
    checking -> ServerCheckPresentation(ServerCheckIndicator.Checking, "Проверяем подключение…")
    result == null -> ServerCheckPresentation(ServerCheckIndicator.Checking, "Проверяем подключение…")
    result == ServerCheckResult.Available ->
        ServerCheckPresentation(ServerCheckIndicator.Available, "Сервер TiniTalk доступен")
    result == ServerCheckResult.WrongServer ->
        ServerCheckPresentation(ServerCheckIndicator.Unavailable, "По этому адресу нет сервера TiniTalk")
    result == ServerCheckResult.ServerOutdated ->
        ServerCheckPresentation(ServerCheckIndicator.Incompatible, "Сервер TiniTalk устарел. Обновите сервер")
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
    val contacts: List<Contact> = emptyList(),
    val contactsRefreshing: Boolean = false,
    val contactsRefreshErrorMessage: String? = null,
    val contactsLoadingMore: Boolean = false,
    val contactsNextCursor: String = "",
    val contactsLoadMoreErrorMessage: String? = null,
    val history: List<CallHistoryItem> = emptyList(),
    val historyLoaded: Boolean = false,
    val historyLoading: Boolean = false,
    val historyLoadingMore: Boolean = false,
    val historyNextBefore: Long = 0,
    val historyLatestId: Long = 0,
    val historyErrorMessage: String? = null,
    val contactHistory: ContactHistoryState = ContactHistoryState(),
    val unreadMissedCount: Int = 0,
    val latestUnreadMissedByContact: Map<String, Long> = emptyMap(),
    val permissions: AppPermissionsState = AppPermissionsState(),
    val errorMessage: String? = null,
    val networkAvailable: Boolean = true,
)

fun MainScreenState.withOfflineSession(serverUrl: String?): MainScreenState = copy(
    restoring = false,
    signingIn = false,
    signedIn = serverUrl != null,
    serverUrl = serverUrl.orEmpty(),
    contactsRefreshing = false,
    contactsLoadingMore = false,
    historyLoading = false,
    historyLoadingMore = false,
    contactHistory = contactHistory.copy(loading = false, loadingMore = false),
    networkAvailable = false,
)

fun MainScreenState.withRefreshedContacts(page: ContactPage): MainScreenState = copy(
    contacts = page.items,
    contactsRefreshing = false,
    contactsRefreshErrorMessage = null,
    contactsLoadingMore = false,
    contactsNextCursor = page.nextCursor,
    contactsLoadMoreErrorMessage = null,
)

fun MainScreenState.withContactsPage(page: ContactPage): MainScreenState = copy(
    contacts = (contacts + page.items).distinctBy(Contact::login),
    contactsLoadingMore = false,
    contactsNextCursor = page.nextCursor,
    contactsLoadMoreErrorMessage = null,
)

fun MainScreenState.withUnreadMissedState(
    unread: CallUnreadState,
    appliedBadgeCount: Int,
): MainScreenState = copy(
    unreadMissedCount = appliedBadgeCount,
    latestUnreadMissedByContact = unread.unreadMissed.associate { it.peerLogin to it.startedAt },
)

internal fun shouldReturnToContactsOnBack(currentPage: Int, contactOpen: Boolean): Boolean =
    currentPage == 1 && !contactOpen

@Composable
fun MainScreen(
    state: MainScreenState,
    contactNameUpdate: ContactNameUpdateState,
    ongoingCall: CallUiState?,
    loginResetKey: Int,
    defaultServerUrl: String,
    onSignIn: (url: String, login: String, token: String) -> Unit,
    onCheckServer: (url: String) -> ServerCheckResult,
    onCheckServerDetails: (url: String) -> ServerCheckDetails,
    onRequestNotifications: () -> Unit,
    onRequestMicrophone: () -> Unit,
    onRequestFullScreenCalls: () -> Unit,
    onRefreshPermissions: () -> Unit,
    onCall: (Contact) -> Unit,
    onRenameContact: (login: String, customName: String?) -> Unit,
    onRenameHandled: () -> Unit,
    onOpenCall: () -> Unit,
    onContactsVisible: () -> Unit,
    onRefreshContacts: () -> Unit,
    onLoadMoreContacts: () -> Unit,
    onContactsRefreshMessageHandled: () -> Unit,
    onHistoryVisible: () -> Unit,
    onLoadMoreHistory: () -> Unit,
    onRetryHistory: () -> Unit,
    onContactHistoryVisible: (login: String) -> Unit,
    onContactHistoryHidden: () -> Unit,
    onLoadMoreContactHistory: () -> Unit,
    onRetryContactHistory: () -> Unit,
    onSignOut: () -> Unit,
) {
    var aboutVisible by rememberSaveable(state.signedIn) { mutableStateOf(false) }
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
                    defaultServerUrl = defaultServerUrl,
                    loading = state.signingIn,
                    errorMessage = state.errorMessage,
                    internetAvailable = state.networkAvailable,
                    onSignIn = onSignIn,
                    onCheckServer = onCheckServer,
                )
                !state.permissions.allRequiredGranted -> PermissionsScreen(
                    permissions = state.permissions,
                    onRequestNotifications = onRequestNotifications,
                    onRequestMicrophone = onRequestMicrophone,
                    onRequestFullScreenCalls = onRequestFullScreenCalls,
                    onRefresh = onRefreshPermissions,
                    onAbout = { aboutVisible = true },
                    onSignOut = onSignOut,
                )
                else -> HomeScreen(
                    state = state,
                    contactNameUpdate = contactNameUpdate,
                    ongoingCall = ongoingCall,
                    onCall = onCall,
                    onRenameContact = onRenameContact,
                    onRenameHandled = onRenameHandled,
                    onOpenCall = onOpenCall,
                    onContactsVisible = onContactsVisible,
                    onRefreshContacts = onRefreshContacts,
                    onLoadMoreContacts = onLoadMoreContacts,
                    onContactsRefreshMessageHandled = onContactsRefreshMessageHandled,
                    onHistoryVisible = onHistoryVisible,
                    onLoadMoreHistory = onLoadMoreHistory,
                    onRetryHistory = onRetryHistory,
                    onContactHistoryVisible = onContactHistoryVisible,
                    onContactHistoryHidden = onContactHistoryHidden,
                    onLoadMoreContactHistory = onLoadMoreContactHistory,
                    onRetryContactHistory = onRetryContactHistory,
                    onAbout = { aboutVisible = true },
                    onSignOut = onSignOut,
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
    defaultServerUrl: String,
    loading: Boolean,
    errorMessage: String?,
    internetAvailable: Boolean,
    onSignIn: (String, String, String) -> Unit,
    onCheckServer: (String) -> ServerCheckResult,
) {
    var login by rememberSaveable(resetKey) { mutableStateOf("") }
    var token by rememberSaveable(resetKey) { mutableStateOf("") }
    var url by rememberSaveable(resetKey, defaultServerUrl) { mutableStateOf(defaultServerUrl) }
    var serverExpanded by rememberSaveable(resetKey) { mutableStateOf(false) }
    var serverCheckResult by remember(resetKey) { mutableStateOf<ServerCheckResult?>(null) }
    var checkingServer by remember(resetKey) { mutableStateOf(false) }
    val serverReady = url.trim().matches(Regex("https?://.+", RegexOption.IGNORE_CASE))
    val serverPresentation = serverCheckPresentation(
        serverReady,
        checkingServer,
        serverCheckResult,
        internetAvailable,
    )
    val canSubmit = internetAvailable && !loading && serverReady && login.isNotBlank() && token.isNotBlank()
    val submit = { if (canSubmit) onSignIn(url, login, token) }
    val keyboardVisible = WindowInsets.isImeVisible

    LaunchedEffect(serverExpanded, url, internetAvailable) {
        if (!serverExpanded) return@LaunchedEffect
        if (!internetAvailable) {
            checkingServer = false
            serverCheckResult = null
            return@LaunchedEffect
        }
        if (!serverReady) {
            checkingServer = false
            serverCheckResult = ServerCheckResult.Unavailable
            return@LaunchedEffect
        }
        checkingServer = true
        serverCheckResult = null
        delay(500)
        serverCheckResult = withContext(Dispatchers.IO) { onCheckServer(url) }
        checkingServer = false
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF111D30), MaterialTheme.colorScheme.background),
                    ),
                )
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = if (keyboardVisible) 12.dp else 28.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().widthIn(max = 420.dp),
                horizontalAlignment = Alignment.Start,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppMark(52.dp)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("TiniTalk", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "Звонки для своих",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(if (keyboardVisible) 16.dp else 28.dp))
                OutlinedTextField(
                    value = login,
                    onValueChange = { login = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Логин") },
                    singleLine = true,
                    enabled = !loading,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Токен") },
                    singleLine = true,
                    enabled = !loading,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                )
                Spacer(Modifier.height(4.dp))
                TextButton(
                    onClick = {
                        if (!serverExpanded) serverCheckResult = null
                        serverExpanded = !serverExpanded
                    },
                    enabled = !loading,
                    modifier = Modifier.align(Alignment.Start),
                ) {
                    Text(if (serverExpanded) "Скрыть настройки сервера" else "Настройки сервера")
                }
                if (serverExpanded) {
                    OutlinedTextField(
                        value = url,
                        onValueChange = {
                            url = it
                            checkingServer = true
                            serverCheckResult = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Адрес сервера") },
                        placeholder = { Text("https://talk.example.com") },
                        supportingText = {
                            Text(
                                serverPresentation.message,
                                color = when (serverPresentation.indicator) {
                                    ServerCheckIndicator.Available -> CallAnswerGreen
                                    ServerCheckIndicator.Incompatible -> BrandGold
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        },
                        trailingIcon = {
                            when (serverPresentation.indicator) {
                                ServerCheckIndicator.Checking -> CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                )
                                ServerCheckIndicator.Available -> Icon(
                                    painterResource(R.drawable.ic_server_available),
                                    contentDescription = "Сервер доступен",
                                    tint = CallAnswerGreen,
                                )
                                ServerCheckIndicator.Unavailable -> Icon(
                                    painterResource(R.drawable.ic_server_unavailable),
                                    contentDescription = "Сервер недоступен",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                ServerCheckIndicator.Incompatible -> Icon(
                                    painterResource(R.drawable.ic_server_incompatible),
                                    contentDescription = "Несовместимая версия",
                                    tint = BrandGold,
                                )
                            }
                        },
                        singleLine = true,
                        enabled = !loading,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                    )
                }
                if (!serverReady && !serverExpanded) {
                    Text(
                        "Укажите адрес сервера в настройках",
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (errorMessage != null) {
                    Spacer(Modifier.height(14.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                    ) {
                        Text(
                            errorMessage,
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                Spacer(Modifier.height(if (keyboardVisible) 12.dp else 20.dp))
                Button(
                    onClick = submit,
                    enabled = canSubmit,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    if (loading) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Войти", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "v ${BuildConfig.COMMIT_HASH}",
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                    fontSize = 10.sp,
                )
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
    onSignOut: () -> Unit,
) {
    AppPage(onAbout = onAbout, onSignOut = onSignOut) {
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
    contactNameUpdate: ContactNameUpdateState,
    ongoingCall: CallUiState?,
    onCall: (Contact) -> Unit,
    onRenameContact: (login: String, customName: String?) -> Unit,
    onRenameHandled: () -> Unit,
    onOpenCall: () -> Unit,
    onContactsVisible: () -> Unit,
    onRefreshContacts: () -> Unit,
    onLoadMoreContacts: () -> Unit,
    onContactsRefreshMessageHandled: () -> Unit,
    onHistoryVisible: () -> Unit,
    onLoadMoreHistory: () -> Unit,
    onRetryHistory: () -> Unit,
    onContactHistoryVisible: (login: String) -> Unit,
    onContactHistoryHidden: () -> Unit,
    onLoadMoreContactHistory: () -> Unit,
    onRetryContactHistory: () -> Unit,
    onAbout: () -> Unit,
    onSignOut: () -> Unit,
) {
    val pagerState = rememberPagerState(pageCount = { 2 })
    val contactsListState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var selectedContactLogin by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedContact = state.contacts.firstOrNull { it.login == selectedContactLogin }

    BackHandler(
        enabled = shouldReturnToContactsOnBack(
            currentPage = pagerState.currentPage,
            contactOpen = selectedContactLogin != null,
        ),
    ) {
        scope.launch { pagerState.animateScrollToPage(0) }
    }

    LaunchedEffect(selectedContactLogin, state.contacts) {
        val login = selectedContactLogin ?: return@LaunchedEffect
        if (state.contacts.none { it.login == login }) {
            selectedContactLogin = null
            scope.launch { snackbarHostState.showSnackbar("Контакт больше недоступен") }
        }
    }
    LaunchedEffect(selectedContactLogin) {
        selectedContactLogin?.let(onContactHistoryVisible) ?: onContactHistoryHidden()
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
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = if (selectedContact == null) Modifier else Modifier.clearAndSetSemantics {},
        ) {
            AppPage(onAbout = onAbout, onSignOut = onSignOut) {
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
                                contacts = state.contacts,
                                latestUnreadMissedByContact = state.latestUnreadMissedByContact,
                                internetAvailable = state.networkAvailable,
                                listState = contactsListState,
                                refreshing = state.contactsRefreshing,
                                loadingMore = state.contactsLoadingMore,
                                nextCursor = state.contactsNextCursor,
                                loadMoreErrorMessage = state.contactsLoadMoreErrorMessage,
                                onRefresh = onRefreshContacts,
                                onLoadMore = onLoadMoreContacts,
                                onContactSelected = { selectedContactLogin = it.login },
                            )
                        } else {
                            HistoryScreen(
                                items = state.history,
                                internetAvailable = state.networkAvailable,
                                loaded = state.historyLoaded,
                                loading = state.historyLoading,
                                loadingMore = state.historyLoadingMore,
                                nextBefore = state.historyNextBefore,
                                errorMessage = state.historyErrorMessage,
                                onLoadMore = onLoadMoreHistory,
                                onRetry = onRetryHistory,
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
        if (selectedContact != null) {
            ContactScreen(
                contact = selectedContact,
                internetAvailable = state.networkAvailable,
                nameUpdate = contactNameUpdate,
                history = state.contactHistory,
                ongoingCall = ongoingCall,
                onBack = { selectedContactLogin = null },
                onCall = onCall,
                onOpenCall = onOpenCall,
                onRename = { customName -> onRenameContact(selectedContact.login, customName) },
                onRenameHandled = onRenameHandled,
                onLoadMoreHistory = onLoadMoreContactHistory,
                onRetryHistory = onRetryContactHistory,
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
    contacts: List<Contact>,
    latestUnreadMissedByContact: Map<String, Long>,
    internetAvailable: Boolean,
    listState: LazyListState,
    refreshing: Boolean,
    loadingMore: Boolean,
    nextCursor: String,
    loadMoreErrorMessage: String?,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onContactSelected: (Contact) -> Unit,
) {
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
                itemsIndexed(contacts, key = { _, contact -> contact.login }) { index, contact ->
                    ContactRow(contact, latestUnreadMissedByContact[contact.login], onContactSelected)
                    if (shouldLoadMoreContacts(
                            index = index,
                            itemCount = contacts.size,
                            nextCursor = nextCursor,
                            loading = loadingMore || refreshing,
                            hasError = loadMoreErrorMessage != null,
                            internetAvailable = internetAvailable,
                        )
                    ) {
                        LaunchedEffect(nextCursor) { onLoadMore() }
                    }
                }
                if (loadMoreErrorMessage != null) {
                    item(key = "contacts-load-error") {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                loadMoreErrorMessage,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            TextButton(onClick = onLoadMore, enabled = internetAvailable) { Text("Повторить") }
                        }
                    }
                }
                if (loadingMore) {
                    item(key = "contacts-loading-more") {
                        Box(Modifier.fillMaxWidth().padding(18.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.size(26.dp), strokeWidth = 2.dp)
                        }
                    }
                }
            }
        }
    }
}

fun shouldLoadMoreContacts(
    index: Int,
    itemCount: Int,
    nextCursor: String,
    loading: Boolean,
    hasError: Boolean,
    internetAvailable: Boolean = true,
): Boolean = internetAvailable && nextCursor.isNotEmpty() && !loading && !hasError && index == maxOf(0, itemCount - 5)

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
            Box(
                modifier = Modifier.size(44.dp).clip(CircleShape).background(CallAnswerGreen),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_call),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(23.dp),
                )
            }
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
private fun ContactRow(contact: Contact, latestUnreadMissedAt: Long?, onOpen: (Contact) -> Unit) {
    val avatarColors = listOf(
        Color(0xFF394A67), Color(0xFF514464), Color(0xFF30514D),
        Color(0xFF60443B), Color(0xFF4E5337), Color(0xFF593F4C),
    )
    val name = contactDisplayName(contact.displayName)
    val missedSubtitle = latestUnreadMissedAt?.let(::missedContactSubtitle)
    val avatarColor = avatarColors[contactColorIndex(contact.login, avatarColors.size)]
    Surface(
        onClick = { onOpen(contact) },
        modifier = Modifier.fillMaxWidth().semantics {
            contentDescription = listOfNotNull("Открыть контакт: $name", missedSubtitle).joinToString(". ")
        },
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.78f)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 82.dp).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(52.dp),
                shape = CircleShape,
                color = avatarColor,
                border = BorderStroke(1.dp, BrandGold.copy(alpha = 0.22f)),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        contactInitial(name, ""),
                        color = Color(0xFFF6E8C0),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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
    onSignOut: () -> Unit,
    content: @Composable () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var logoutDialog by remember { mutableStateOf(false) }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().height(68.dp).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppMark(42.dp)
                Spacer(Modifier.width(12.dp))
                Text("TiniTalk", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.semantics { contentDescription = "Меню" },
                    ) {
                        Text("⋮", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                        modifier = Modifier.widthIn(min = 240.dp),
                    ) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "О программе",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onAbout()
                            },
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(R.drawable.ic_info),
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp),
                                )
                            },
                            modifier = Modifier.heightIn(min = 64.dp),
                            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
                            colors = MenuDefaults.itemColors(leadingIconColor = BrandGold),
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "Выйти из аккаунта",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                logoutDialog = true
                            },
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(R.drawable.ic_logout),
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp),
                                )
                            },
                            modifier = Modifier.heightIn(min = 64.dp),
                            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
                            colors = MenuDefaults.itemColors(
                                textColor = CallRejectRed,
                                leadingIconColor = CallRejectRed,
                            ),
                        )
                    }
                }
            }
            Box(modifier = Modifier.weight(1f)) { content() }
        }
    }
    if (logoutDialog) {
        AlertDialog(
            onDismissRequest = { logoutDialog = false },
            title = { Text("Выйти из аккаунта?") },
            text = { Text("Чтобы снова принимать звонки, потребуется войти ещё раз.") },
            confirmButton = {
                Button(
                    onClick = {
                        logoutDialog = false
                        onSignOut()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CallRejectRed),
                ) { Text("Выйти") }
            },
            dismissButton = { TextButton(onClick = { logoutDialog = false }) { Text("Отмена") } },
        )
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
