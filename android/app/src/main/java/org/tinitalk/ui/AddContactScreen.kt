package org.tinitalk.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.tinitalk.R
import org.tinitalk.data.AccountId

@Composable
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
internal fun AddContactScreen(
    accounts: List<AccountSummary>,
    loading: Boolean,
    errorMessage: String?,
    internetAvailable: Boolean,
    onBack: () -> Unit,
    onInputChanged: () -> Unit,
    onAdd: (AccountId, String, String) -> Unit,
) {
    val keyboardVisible = WindowInsets.isImeVisible
    var selectedAccountId by rememberSaveable(accounts.map { it.id.value }) {
        mutableStateOf(accounts.singleOrNull()?.id?.value)
    }
    var login by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var serverMenuVisible by rememberSaveable { mutableStateOf(false) }
    val selectedAccount = accounts.firstOrNull { it.id.value == selectedAccountId }
    val trimmedName = name.trim()
    val nameLength = trimmedName.codePointCount(0, trimmedName.length)
    val validName = trimmedName.isNotEmpty() && nameLength <= 64
    val canSubmit = internetAvailable && !loading && selectedAccount != null && login.isNotBlank() && validName
    val submit = {
        if (canSubmit) onAdd(checkNotNull(selectedAccount).id, login.trim(), trimmedName)
    }

    BackHandler { if (!loading) onBack() }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0xFF111D30), MaterialTheme.colorScheme.background)))
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState()),
        ) {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CompositionLocalProvider(LocalRippleConfiguration provides null) {
                        IconButton(onClick = onBack, enabled = !loading) {
                            Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Назад")
                        }
                    }
                    Text("Добавить контакт", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
                        .padding(top = 20.dp, bottom = if (keyboardVisible) 12.dp else 28.dp),
                ) {
                    if (accounts.size > 1) {
                        Box(Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = selectedAccount?.let { serverAddress(it.serverUrl) }.orEmpty(),
                                onValueChange = {},
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Сервер") },
                                placeholder = { Text("Выберите сервер") },
                                trailingIcon = {
                                    Icon(
                                        painterResource(R.drawable.ic_chevron_right),
                                        contentDescription = null,
                                        modifier = Modifier.size(22.dp).rotate(90f),
                                    )
                                },
                                readOnly = true,
                                enabled = !loading,
                                singleLine = true,
                            )
                            Box(
                                Modifier.matchParentSize().clickable(enabled = !loading) {
                                    serverMenuVisible = true
                                },
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                    OutlinedTextField(
                        value = login,
                        onValueChange = {
                            login = it
                            onInputChanged()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Логин") },
                        enabled = !loading,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = name,
                        onValueChange = {
                            name = it
                            onInputChanged()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Имя в контактах") },
                        supportingText = if (nameLength > 64) {
                            { Text("Не больше 64 символов") }
                        } else {
                            null
                        },
                        isError = nameLength > 64,
                        enabled = !loading,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                    )
                    errorMessage?.let { message ->
                        Spacer(Modifier.height(14.dp))
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.errorContainer,
                        ) {
                            Text(
                                message,
                                modifier = Modifier.padding(16.dp),
                                color = MaterialTheme.colorScheme.onErrorContainer,
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
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        } else {
                            Text("Добавить", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }

    if (serverMenuVisible) {
        ModalBottomSheet(onDismissRequest = { serverMenuVisible = false }) {
            Text(
                "Выберите сервер",
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            accounts.forEach { account ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            selectedAccountId = account.id.value
                            serverMenuVisible = false
                            onInputChanged()
                        }
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(serverAddress(account.serverUrl), fontWeight = FontWeight.SemiBold)
                        Text(
                            account.login,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
