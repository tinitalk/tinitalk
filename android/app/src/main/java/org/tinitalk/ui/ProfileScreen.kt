package org.tinitalk.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.tinitalk.R
import org.tinitalk.data.AccountId
import org.tinitalk.ui.theme.CallRejectRed

@Composable
internal fun ProfileScreen(
    accounts: List<AccountSummary>,
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
                IconButton(onClick = onBack) {
                    Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Назад")
                }
                Text("Профиль", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(accounts, key = { it.id.value }) { account ->
                    Surface(shape = MaterialTheme.shapes.large, tonalElevation = 2.dp) {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Text(account.displayName?.takeIf(String::isNotBlank) ?: account.login, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(4.dp))
                            Text(account.login, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(account.serverUrl, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(10.dp))
                            TextButton(onClick = { pendingRemoval = account.id.value }) {
                                Icon(painterResource(R.drawable.ic_logout), contentDescription = null)
                                Text("Выйти")
                            }
                        }
                    }
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
