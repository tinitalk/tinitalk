package org.tinitalk.ui

import org.tinitalk.i18n.appString

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.tinitalk.R
import org.tinitalk.data.AccountId
import org.tinitalk.data.ServerCheckDetails
import org.tinitalk.data.ServerCheckResult
import org.tinitalk.ui.theme.BrandGold
import org.tinitalk.ui.theme.CallAnswerGreen
import org.tinitalk.ui.theme.CallRejectRed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun ProfileScreen(
    accounts: List<AccountSummary>,
    internetAvailable: Boolean,
    onCheckServer: (String) -> ServerCheckDetails,
    onCheckPasswordSet: (AccountId) -> Boolean? = { null },
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onRemoveAccount: (AccountId) -> Unit,
    passwordChanging: Boolean = false,
    passwordErrorMessage: String? = null,
    passwordChangeCompletionKey: Int = 0,
    onChangePassword: (AccountId, String, String) -> Unit = { _, _, _ -> },
    retryAtMillis: Long = 0,
) {
    var pendingRemoval by rememberSaveable { mutableStateOf<String?>(null) }
    var passwordAccount by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedPasswordSet by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(passwordChangeCompletionKey) {
        if (passwordChangeCompletionKey > 0) {
            passwordAccount = null
            selectedPasswordSet = true
        }
    }
    BackHandler(onBack = onBack)
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().height(if (compactLandscape()) 48.dp else 64.dp).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CompositionLocalProvider(LocalRippleConfiguration provides null) {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = appString(R.string.text_back_101))
                    }
                }
                Text(appString(R.string.text_profile_308), modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                CompositionLocalProvider(LocalRippleConfiguration provides null) {
                    AddListButton(onClick = onAdd, enabled = true)
                }
            }
            LazyColumn(
                modifier = Modifier.weight(1f).align(Alignment.CenterHorizontally).widthIn(max = 640.dp).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(accounts, key = { it.id.value }) { account ->
                    ProfileAccountCard(
                        account = account,
                        internetAvailable = internetAvailable,
                        onCheckServer = onCheckServer,
                        onCheckPasswordSet = onCheckPasswordSet,
                        onRemove = { pendingRemoval = account.id.value },
                        onChangePassword = { passwordSet ->
                            selectedPasswordSet = passwordSet
                            passwordAccount = account.id.value
                        },
                    )
                }
            }
        }
    }
    pendingRemoval?.let { value ->
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { Text(appString(R.string.text_sign_out_of_this_account_321)) },
            text = { Text(appString(R.string.text_you_will_need_to_sign_in_again_to_receive_calls_322)) },
            confirmButton = {
                Button(
                    onClick = { pendingRemoval?.let { onRemoveAccount(AccountId(it)) }; pendingRemoval = null },
                    colors = ButtonDefaults.buttonColors(containerColor = CallRejectRed),
                ) { Text(appString(R.string.text_sign_out_323)) }
            },
            dismissButton = { TextButton(onClick = { pendingRemoval = null }) { Text(appString(R.string.text_cancel_12)) } },
        )
    }
    passwordAccount?.let { value ->
        ChangePasswordDialog(
            loading = passwordChanging,
            errorMessage = passwordErrorMessage,
            passwordSet = selectedPasswordSet,
            retryAtMillis = retryAtMillis,
            onDismiss = { if (!passwordChanging) passwordAccount = null },
            onSubmit = { current, new -> onChangePassword(AccountId(value), current, new) },
        )
    }
}

@Composable
private fun ProfileAccountCard(
    account: AccountSummary,
    internetAvailable: Boolean,
    onCheckServer: (String) -> ServerCheckDetails,
    onCheckPasswordSet: (AccountId) -> Boolean?,
    onRemove: () -> Unit,
    onChangePassword: (Boolean?) -> Unit,
) {
    var details by remember(account.serverUrl) { mutableStateOf<ServerCheckDetails?>(null) }
    var checking by remember(account.serverUrl) { mutableStateOf(internetAvailable) }
    var passwordSet by remember(account.id, account.passwordSet) { mutableStateOf(account.passwordSet) }
    val presentation = serverCheckPresentation(
        serverReady = account.serverUrl.isNotBlank(),
        checking = checking,
        result = details?.result,
        internetAvailable = internetAvailable,
    )
    val statusText = when {
        !internetAvailable -> appString(R.string.text_no_internet_324)
        else -> when (presentation.indicator) {
            ServerCheckIndicator.Checking -> appString(R.string.text_checking_325)
            ServerCheckIndicator.Available -> appString(R.string.text_server_available_119)
            ServerCheckIndicator.Unavailable -> appString(R.string.text_server_unavailable_120)
            ServerCheckIndicator.Incompatible -> appString(R.string.text_incompatible_version_121)
        }
    }
    val incompatibleColor = if (isSystemInDarkTheme()) Color(0xFFFFA726) else Color(0xFFC45100)
    val statusColor = when {
        !internetAvailable -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> when (presentation.indicator) {
            ServerCheckIndicator.Checking -> MaterialTheme.colorScheme.onSurfaceVariant
            ServerCheckIndicator.Available -> CallAnswerGreen
            ServerCheckIndicator.Unavailable -> CallRejectRed
            ServerCheckIndicator.Incompatible -> incompatibleColor
        }
    }

    LaunchedEffect(account.id, account.serverUrl, internetAvailable) {
        if (!internetAvailable) {
            checking = false
            details = null
            return@LaunchedEffect
        }
        checking = true
        details = null
        details = withContext(Dispatchers.IO) { onCheckServer(account.serverUrl) }
        passwordSet = if (details?.result == ServerCheckResult.Available) {
            withContext(Dispatchers.IO) { runCatching { onCheckPasswordSet(account.id) }.getOrNull() }
        } else null
        checking = false
    }

    Surface(shape = MaterialTheme.shapes.large, tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    account.login,
                    modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.SemiBold,
                )
                Box(
                    modifier = Modifier.size(32.dp).clickable(onClick = onRemove),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(R.drawable.ic_logout),
                        contentDescription = appString(R.string.text_sign_out_323),
                        modifier = Modifier.size(24.dp),
                        tint = BrandGold,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    serverAddress(account.serverUrl),
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Light,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val apiVersion = details?.apiVersion
                val commit = details?.commit
                if (apiVersion != null && !commit.isNullOrBlank()) {
                    Spacer(Modifier.size(8.dp))
                    Text(
                        "API v$apiVersion ($commit)",
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Light,
                        maxLines = 1,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (presentation.indicator) {
                    ServerCheckIndicator.Checking -> CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 1.5.dp,
                    )
                    ServerCheckIndicator.Unavailable -> Icon(
                        painterResource(R.drawable.ic_server_unavailable),
                        contentDescription = statusText,
                        modifier = Modifier.size(16.dp),
                        tint = statusColor,
                    )
                    ServerCheckIndicator.Incompatible -> Icon(
                        painterResource(R.drawable.ic_server_incompatible),
                        contentDescription = statusText,
                        modifier = Modifier.size(16.dp),
                        tint = statusColor,
                    )
                    ServerCheckIndicator.Available -> Unit
                }
                if (presentation.indicator != ServerCheckIndicator.Available) {
                    Spacer(Modifier.size(6.dp))
                }
                Text(
                    statusText,
                    modifier = Modifier.weight(1f),
                    color = statusColor,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (internetAvailable && !checking && details?.result == ServerCheckResult.Available && passwordSet != null) {
                    CompositionLocalProvider(LocalRippleConfiguration provides null) {
                        TextButton(onClick = { onChangePassword(passwordSet) },
                            modifier = Modifier.height(32.dp),
                            contentPadding = PaddingValues(start = 12.dp, end = 0.dp)) {
                            Text(if (passwordSet == true) appString(R.string.text_change_password_326) else appString(R.string.text_set_password_327))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChangePasswordDialog(
    loading: Boolean,
    errorMessage: String?,
    passwordSet: Boolean?,
    retryAtMillis: Long,
    onDismiss: () -> Unit,
    onSubmit: (String, String) -> Unit,
) {
    var currentPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var validationMessage by remember { mutableStateOf<String?>(null) }
    val retry = retrySeconds(retryAtMillis)
    val landscape = landscapeLayout()
    val passwordHint: @Composable () -> Unit = {
        Text(appString(R.string.text_at_least_8_characters_we_recommend_combining_lowercase_and_upperc_318),
            style = MaterialTheme.typography.bodySmall,
            textAlign = if (landscape) androidx.compose.ui.text.style.TextAlign.Center else androidx.compose.ui.text.style.TextAlign.Start)
    }
    AccountPasswordDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (passwordSet == false) appString(R.string.text_set_password_327) else appString(R.string.text_change_password_326)) },
        landscapeDescription = passwordHint,
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!landscape) passwordHint()
                if (passwordSet != false) {
                    OutlinedTextField(
                        currentPassword,
                        { currentPassword = it; validationMessage = null },
                        label = { Text(appString(R.string.text_current_password_328)) },
                        singleLine = true,
                        enabled = !loading,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                }
                OutlinedTextField(
                    newPassword,
                    { newPassword = it; validationMessage = null },
                    label = { Text(appString(R.string.text_new_password_319)) },
                    singleLine = true,
                    enabled = !loading,
                    visualTransformation = PasswordVisualTransformation(),
                )
                OutlinedTextField(
                    confirmation,
                    { confirmation = it; validationMessage = null },
                    label = { Text(appString(R.string.text_repeat_password_320)) },
                    singleLine = true,
                    enabled = !loading,
                    visualTransformation = PasswordVisualTransformation(),
                )
                (validationMessage ?: if (retryAtMillis > 0) {
                    if (retry > 0) appString(R.string.text_try_again_in_value_s_122, retry) else appString(R.string.text_you_can_try_again_123)
                } else errorMessage)?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !loading && retry == 0L && (passwordSet == false || currentPassword.isNotEmpty()),
                onClick = {
                    validationMessage = personalPasswordError(newPassword)
                        ?: passwordConfirmationError(newPassword, confirmation)
                    if (validationMessage == null) onSubmit(currentPassword, newPassword)
                },
            ) {
                if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(appString(R.string.text_save_245))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !loading) { Text(appString(R.string.text_cancel_12)) } },
    )
}
