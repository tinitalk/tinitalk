package org.tinitalk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.tinitalk.R
import org.tinitalk.call.CallDirection
import org.tinitalk.call.CallPhase
import org.tinitalk.call.CallUiState
import org.tinitalk.call.ConnectionHealth
import org.tinitalk.data.Contact
import org.tinitalk.permissions.AppPermissionsState
import org.tinitalk.ui.theme.CallAnswerGreen
import org.tinitalk.ui.theme.CallRejectRed

data class MainScreenState(
    val restoring: Boolean = true,
    val signingIn: Boolean = false,
    val signedIn: Boolean = false,
    val contacts: List<Contact> = emptyList(),
    val permissions: AppPermissionsState = AppPermissionsState(),
    val errorMessage: String? = null,
)

@Composable
fun MainScreen(
    state: MainScreenState,
    ongoingCall: CallUiState?,
    loginResetKey: Int,
    onSignIn: (url: String, login: String, token: String) -> Unit,
    onRequestNotifications: () -> Unit,
    onRequestMicrophone: () -> Unit,
    onRequestFullScreenCalls: () -> Unit,
    onRefreshPermissions: () -> Unit,
    onCall: (Contact) -> Unit,
    onOpenCall: () -> Unit,
    onSignOut: () -> Unit,
) {
    when {
        state.restoring -> LoadingScreen()
        !state.signedIn -> LoginScreen(loginResetKey, state.signingIn, state.errorMessage, onSignIn)
        !state.permissions.allRequiredGranted -> PermissionsScreen(
            permissions = state.permissions,
            onRequestNotifications = onRequestNotifications,
            onRequestMicrophone = onRequestMicrophone,
            onRequestFullScreenCalls = onRequestFullScreenCalls,
            onRefresh = onRefreshPermissions,
            onSignOut = onSignOut,
        )
        else -> ContactsScreen(state.contacts, ongoingCall, onCall, onOpenCall, onSignOut)
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
private fun LoginScreen(
    resetKey: Int,
    loading: Boolean,
    errorMessage: String?,
    onSignIn: (String, String, String) -> Unit,
) {
    var login by rememberSaveable(resetKey) { mutableStateOf("") }
    var token by rememberSaveable(resetKey) { mutableStateOf("") }
    var url by rememberSaveable(resetKey) { mutableStateOf("https://") }
    var serverExpanded by rememberSaveable(resetKey) { mutableStateOf(false) }
    val serverReady = url.trim().matches(Regex("https?://.+", RegexOption.IGNORE_CASE))
    val canSubmit = !loading && serverReady && login.isNotBlank() && token.isNotBlank()
    val submit = { if (canSubmit) onSignIn(url, login, token) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 28.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().widthIn(max = 420.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AppMark(88.dp)
                Spacer(Modifier.height(22.dp))
                Text("TiniTalk", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Звонки для своих",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(36.dp))
                OutlinedTextField(
                    value = login,
                    onValueChange = { login = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Логин") },
                    singleLine = true,
                    enabled = !loading,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )
                Spacer(Modifier.height(14.dp))
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
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = { serverExpanded = !serverExpanded },
                    enabled = !loading,
                    modifier = Modifier.align(Alignment.Start),
                ) {
                    Text(if (serverExpanded) "Скрыть настройки сервера" else "Настройки сервера")
                }
                if (serverExpanded) {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Адрес сервера") },
                        placeholder = { Text("https://talk.example.com") },
                        supportingText = { Text("Адрес вашего сервера TiniTalk") },
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
                Spacer(Modifier.height(22.dp))
                Button(
                    onClick = submit,
                    enabled = canSubmit,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    if (loading) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Войти", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
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
    onSignOut: () -> Unit,
) {
    AppPage(onSignOut = onSignOut) {
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
                title = "Полноэкранный вызов",
                description = "Показывать звонок поверх заблокированного экрана",
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
        Row(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            if (granted) {
                Text("Разрешено", color = CallAnswerGreen, fontWeight = FontWeight.SemiBold)
            } else {
                FilledTonalButton(onClick = onRequest) { Text("Разрешить") }
            }
        }
    }
}

@Composable
private fun ContactsScreen(
    contacts: List<Contact>,
    ongoingCall: CallUiState?,
    onCall: (Contact) -> Unit,
    onOpenCall: () -> Unit,
    onSignOut: () -> Unit,
) {
    AppPage(onSignOut = onSignOut) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (ongoingCall != null) {
                OngoingCallBanner(ongoingCall, onOpenCall)
            }
            if (contacts.isEmpty()) {
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Контактов пока нет", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Добавьте абонентов в настройках сервера.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Surface(
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 1.dp,
                ) {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        itemsIndexed(contacts, key = { _, contact -> contact.login }) { index, contact ->
                            ContactRow(contact, onCall, enabled = ongoingCall == null)
                            if (index < contacts.lastIndex) {
                                HorizontalDivider(modifier = Modifier.padding(start = 86.dp), color = MaterialTheme.colorScheme.outlineVariant)
                            }
                        }
                    }
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
private fun ContactRow(contact: Contact, onCall: (Contact) -> Unit, enabled: Boolean) {
    val avatarColors = listOf(
        Color(0xFF315EA8),
        Color(0xFF6D4C9F),
        Color(0xFF287D78),
        Color(0xFFB05A44),
        Color(0xFF5F6F3A),
        Color(0xFF8A5268),
    )
    val avatarColor = avatarColors[contactColorIndex(contact.login, avatarColors.size)]
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 78.dp).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(52.dp).clip(CircleShape).background(avatarColor),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                contactInitial(contact.displayName, contact.login),
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.width(18.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                contact.displayName.ifBlank { contact.login },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                contact.login,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(
            onClick = { onCall(contact) },
            enabled = enabled,
            modifier = Modifier
                .size(54.dp)
                .background(CallAnswerGreen.copy(alpha = if (enabled) 1f else 0.35f), CircleShape),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_call),
                contentDescription = if (enabled) {
                    "Позвонить: ${contact.displayName.ifBlank { contact.login }}"
                } else {
                    "Сначала завершите текущий звонок"
                },
                tint = Color.White,
                modifier = Modifier.size(25.dp),
            )
        }
    }
}

@Composable
private fun AppPage(onSignOut: () -> Unit, content: @Composable () -> Unit) {
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
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("Выйти из аккаунта") },
                            onClick = {
                                menuExpanded = false
                                logoutDialog = true
                            },
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
private fun AppMark(size: androidx.compose.ui.unit.Dp) {
    Surface(
        modifier = Modifier.size(size),
        shape = RoundedCornerShape(size / 3f),
        color = MaterialTheme.colorScheme.primary,
        shadowElevation = 3.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(R.drawable.ic_call),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(size * 0.52f),
            )
        }
    }
}
