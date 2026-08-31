package org.tinitalk.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.tinitalk.R
import org.tinitalk.data.ServerCheckResult
import org.tinitalk.ui.theme.BrandGold
import org.tinitalk.ui.theme.CallAnswerGreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun AddAccountScreen(
    resetKey: Int,
    defaultServerUrl: String,
    loading: Boolean,
    errorMessage: String?,
    internetAvailable: Boolean,
    onBack: () -> Unit,
    onAdd: (String, String, String) -> Unit,
    onCheckServer: (String) -> ServerCheckResult,
) {
    val keyboardVisible = WindowInsets.isImeVisible
    BackHandler { if (!loading) onBack() }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color(0xFF111D30), MaterialTheme.colorScheme.background)),
            ).statusBarsPadding().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = if (keyboardVisible) 12.dp else 28.dp),
        ) {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack, enabled = !loading) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Назад")
                    }
                    Text("Добавить аккаунт", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(20.dp))
                AccountCredentialsForm(resetKey, defaultServerUrl, true, loading, errorMessage, internetAvailable, "Добавить", keyboardVisible, onAdd, onCheckServer)
            }
        }
    }
}

@Composable
internal fun AccountCredentialsForm(
    resetKey: Int,
    defaultServerUrl: String,
    serverInitiallyExpanded: Boolean,
    loading: Boolean,
    errorMessage: String?,
    internetAvailable: Boolean,
    submitLabel: String,
    compactSpacing: Boolean,
    onSubmit: (String, String, String) -> Unit,
    onCheckServer: (String) -> ServerCheckResult,
) {
    var login by rememberSaveable(resetKey) { mutableStateOf("") }
    var token by rememberSaveable(resetKey) { mutableStateOf("") }
    var url by rememberSaveable(resetKey, defaultServerUrl) { mutableStateOf(defaultServerUrl) }
    var serverExpanded by rememberSaveable(resetKey, serverInitiallyExpanded) { mutableStateOf(serverInitiallyExpanded) }
    var serverCheckResult by remember(resetKey) { mutableStateOf<ServerCheckResult?>(null) }
    var checkingServer by remember(resetKey) { mutableStateOf(false) }
    val serverReady = url.trim().matches(Regex("https?://.+", RegexOption.IGNORE_CASE))
    val presentation = serverCheckPresentation(serverReady, checkingServer, serverCheckResult, internetAvailable)
    val canSubmit = internetAvailable && !loading && serverReady && login.isNotBlank() && token.isNotBlank()
    val submit = { if (canSubmit) onSubmit(url, login, token) }
    LaunchedEffect(serverExpanded, url, internetAvailable) {
        if (!serverExpanded) return@LaunchedEffect
        if (!internetAvailable) { checkingServer = false; serverCheckResult = null; return@LaunchedEffect }
        if (!serverReady) { checkingServer = false; serverCheckResult = ServerCheckResult.Unavailable; return@LaunchedEffect }
        checkingServer = true
        serverCheckResult = null
        delay(500)
        serverCheckResult = withContext(Dispatchers.IO) { onCheckServer(url) }
        checkingServer = false
    }
    OutlinedTextField(login, { login = it }, Modifier.fillMaxWidth(), label = { Text("Логин") }, singleLine = true, enabled = !loading, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next))
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth(), label = { Text("Токен") }, singleLine = true, enabled = !loading, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { submit() }))
    Spacer(Modifier.height(4.dp))
    TextButton(onClick = { if (!serverExpanded) serverCheckResult = null; serverExpanded = !serverExpanded }, enabled = !loading) { Text(if (serverExpanded) "Скрыть настройки сервера" else "Настройки сервера") }
    if (serverExpanded) OutlinedTextField(
        url, { url = it; checkingServer = true; serverCheckResult = null }, Modifier.fillMaxWidth(), label = { Text("Адрес сервера") }, placeholder = { Text("https://talk.example.com") },
        supportingText = { Text(presentation.message, color = when (presentation.indicator) { ServerCheckIndicator.Available -> CallAnswerGreen; ServerCheckIndicator.Incompatible -> BrandGold; else -> MaterialTheme.colorScheme.onSurfaceVariant }) },
        trailingIcon = { when (presentation.indicator) {
            ServerCheckIndicator.Checking -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            ServerCheckIndicator.Available -> Icon(painterResource(R.drawable.ic_server_available), "Сервер доступен", tint = CallAnswerGreen)
            ServerCheckIndicator.Unavailable -> Icon(painterResource(R.drawable.ic_server_unavailable), "Сервер недоступен", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            ServerCheckIndicator.Incompatible -> Icon(painterResource(R.drawable.ic_server_incompatible), "Несовместимая версия", tint = BrandGold)
        } }, singleLine = true, enabled = !loading, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { submit() }),
    )
    if (!serverReady && !serverExpanded) Text("Укажите адрес сервера в настройках", modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    errorMessage?.let {
        Spacer(Modifier.height(14.dp))
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.errorContainer) { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium) }
    }
    Spacer(Modifier.height(if (compactSpacing) 12.dp else 20.dp))
    Button(onClick = submit, enabled = canSubmit, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) {
        if (loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) else Text(submitLabel, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
}
