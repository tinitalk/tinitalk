package org.tinitalk.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import org.tinitalk.R
import org.tinitalk.data.AccountId
import org.tinitalk.data.ServerCheckDetails
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
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onRemoveAccount: (AccountId) -> Unit,
) {
    var pendingRemoval by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(onBack = onBack)
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CompositionLocalProvider(LocalRippleConfiguration provides null) {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Назад")
                    }
                }
                Text("Профиль", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(accounts, key = { it.id.value }) { account ->
                    ProfileAccountCard(
                        account = account,
                        internetAvailable = internetAvailable,
                        onCheckServer = onCheckServer,
                        onRemove = { pendingRemoval = account.id.value },
                    )
                }
                item {
                    Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) { Text("Добавить") }
                }
            }
        }
    }
    pendingRemoval?.let { value ->
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { Text("Выйти из аккаунта?") },
            text = { Text("Чтобы снова принимать звонки, потребуется войти ещё раз.") },
            confirmButton = {
                Button(
                    onClick = { pendingRemoval?.let { onRemoveAccount(AccountId(it)) }; pendingRemoval = null },
                    colors = ButtonDefaults.buttonColors(containerColor = CallRejectRed),
                ) { Text("Выйти") }
            },
            dismissButton = { TextButton(onClick = { pendingRemoval = null }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun ProfileAccountCard(
    account: AccountSummary,
    internetAvailable: Boolean,
    onCheckServer: (String) -> ServerCheckDetails,
    onRemove: () -> Unit,
) {
    var details by remember(account.serverUrl) { mutableStateOf<ServerCheckDetails?>(null) }
    var checking by remember(account.serverUrl) { mutableStateOf(internetAvailable) }
    val presentation = serverCheckPresentation(
        serverReady = account.serverUrl.isNotBlank(),
        checking = checking,
        result = details?.result,
        internetAvailable = internetAvailable,
    )
    val statusText = when {
        !internetAvailable -> "Нет интернета"
        else -> when (presentation.indicator) {
            ServerCheckIndicator.Checking -> "Проверяем…"
            ServerCheckIndicator.Available -> "Сервер доступен"
            ServerCheckIndicator.Unavailable -> "Сервер недоступен"
            ServerCheckIndicator.Incompatible -> "Несовместимая версия"
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

    LaunchedEffect(account.serverUrl, internetAvailable) {
        if (!internetAvailable) {
            checking = false
            details = null
            return@LaunchedEffect
        }
        checking = true
        details = null
        details = withContext(Dispatchers.IO) { onCheckServer(account.serverUrl) }
        checking = false
    }

    Surface(shape = MaterialTheme.shapes.large, tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    account.displayName?.takeIf(String::isNotBlank) ?: account.login,
                    modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.SemiBold,
                )
                Box(
                    modifier = Modifier.size(32.dp).clickable(onClick = onRemove),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(R.drawable.ic_logout),
                        contentDescription = "Выйти",
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
                    buildAnnotatedString {
                        withStyle(
                            SpanStyle(
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.SemiBold,
                            ),
                        ) {
                            append(account.login)
                        }
                        withStyle(
                            SpanStyle(
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                                fontWeight = FontWeight.Light,
                            ),
                        ) {
                            append("@")
                            append(serverAddress(account.serverUrl))
                        }
                    },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.Bottom,
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
        }
    }
}
