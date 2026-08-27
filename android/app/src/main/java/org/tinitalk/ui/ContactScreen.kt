package org.tinitalk.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.tinitalk.R
import org.tinitalk.call.CallUiState
import org.tinitalk.data.Contact
import org.tinitalk.ui.theme.BrandGold
import org.tinitalk.ui.theme.CallAnswerGreen
import java.time.Instant
import java.time.ZoneId

private val contactAvatarColors = listOf(
    Color(0xFF394A67),
    Color(0xFF514464),
    Color(0xFF30514D),
    Color(0xFF60443B),
    Color(0xFF4E5337),
    Color(0xFF593F4C),
)

@Composable
fun ContactScreen(
    contact: Contact,
    nameUpdate: ContactNameUpdateState,
    history: ContactHistoryState,
    ongoingCall: CallUiState?,
    onBack: () -> Unit,
    onCall: (Contact) -> Unit,
    onOpenCall: () -> Unit,
    onRename: (customName: String?) -> Unit,
    onRenameHandled: () -> Unit,
    onLoadMoreHistory: () -> Unit,
    onRetryHistory: () -> Unit,
) {
    var renameVisible by rememberSaveable(contact.login) { mutableStateOf(false) }
    val name = contactDisplayName(contact.displayName)
    val action = contactCallAction(contact.login, ongoingCall)
    val relevantUpdate = nameUpdate.takeIf { it.login == contact.login }
    val now = Instant.now()
    val zone = ZoneId.systemDefault()

    LaunchedEffect(renameVisible, relevantUpdate?.completed) {
        if (renameVisible && relevantUpdate?.completed == true) {
            renameVisible = false
            onRenameHandled()
        }
    }

    BackHandler(enabled = !renameVisible, onBack = onBack)
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) awaitPointerEvent()
                    }
                },
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding(),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CompositionLocalProvider(LocalRippleConfiguration provides null) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_arrow_back),
                                contentDescription = "Назад",
                            )
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                    Text("Контакт", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 22.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    item(key = "contact-profile") {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            ContactAvatar(contact, name, 104.dp)
                            Spacer(Modifier.height(22.dp))
                            Surface(
                                onClick = {
                                    onRenameHandled()
                                    renameVisible = true
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .widthIn(max = 420.dp)
                                    .heightIn(min = 56.dp)
                                    .semantics {
                                        contentDescription = "Имя контакта: $name. Нажмите, чтобы изменить"
                                    },
                                shape = RoundedCornerShape(18.dp),
                                color = Color.Transparent,
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = name,
                                        modifier = Modifier.weight(1f, fill = false),
                                        style = MaterialTheme.typography.headlineMedium,
                                        fontSize = 28.sp,
                                        fontWeight = FontWeight.Bold,
                                        textAlign = TextAlign.Center,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Icon(
                                        painter = painterResource(R.drawable.ic_edit),
                                        contentDescription = null,
                                        tint = BrandGold,
                                        modifier = Modifier.size(22.dp),
                                    )
                                }
                            }
                            Text(
                                "Нажмите на имя, чтобы изменить",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(34.dp))
                            Button(
                                onClick = { if (action.opensCurrentCall) onOpenCall() else onCall(contact) },
                                enabled = action.enabled,
                                modifier = Modifier.fillMaxWidth().widthIn(max = 380.dp).heightIn(min = 58.dp),
                                shape = RoundedCornerShape(20.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = CallAnswerGreen,
                                    contentColor = Color.White,
                                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_call),
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp),
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    action.label,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    textAlign = TextAlign.Center,
                                    maxLines = 2,
                                )
                            }
                            Spacer(Modifier.height(22.dp))
                        }
                    }
                    item(key = "contact-history-title") {
                        Text(
                            "История звонков",
                            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 4.dp, bottom = 4.dp),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    if (history.errorMessage != null) {
                        item(key = "contact-history-error") {
                            ContactHistoryMessage(
                                message = history.errorMessage,
                                action = "Повторить",
                                onAction = onRetryHistory,
                                error = true,
                            )
                        }
                    }
                    when {
                        !history.loaded && history.items.isEmpty() -> item(key = "contact-history-loading") {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(30.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                            }
                        }
                        history.loaded && history.items.isEmpty() && history.errorMessage == null ->
                            item(key = "contact-history-empty") {
                                ContactHistoryMessage("Звонков с этим контактом пока не было")
                            }
                        else -> itemsIndexed(
                            items = history.items,
                            key = { _, item -> "contact-history-${item.id}" },
                        ) { index, item ->
                            Column(modifier = Modifier.fillMaxWidth()) {
                                val day = historyDayLabel(item.startedAt, now, zone)
                                if (index == 0 || day != historyDayLabel(history.items[index - 1].startedAt, now, zone)) {
                                    Text(
                                        text = day,
                                        modifier = Modifier.padding(
                                            start = 4.dp,
                                            top = if (index == 0) 2.dp else 12.dp,
                                            bottom = 8.dp,
                                        ),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                HistoryRow(item, showPeer = false)
                                if (shouldLoadMoreHistory(
                                        index = index,
                                        itemCount = history.items.size,
                                        nextBefore = history.nextBefore,
                                        loading = history.loadingMore,
                                        hasError = history.errorMessage != null,
                                    )
                                ) {
                                    LaunchedEffect(history.nextBefore) { onLoadMoreHistory() }
                                }
                            }
                        }
                    }
                    if (history.loadingMore) {
                        item(key = "contact-history-loading-more") {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(18.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                            }
                        }
                    }
                }
            }
        }
    }

    if (renameVisible) {
        RenameContactDialog(
            contact = contact,
            saving = relevantUpdate?.saving == true,
            errorMessage = relevantUpdate?.errorMessage,
            onDismiss = {
                renameVisible = false
                onRenameHandled()
            },
            onRename = onRename,
            onErrorCleared = onRenameHandled,
        )
    }
}

@Composable
private fun ContactHistoryMessage(
    message: String,
    action: String? = null,
    onAction: () -> Unit = {},
    error: Boolean = false,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                message,
                color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge,
            )
            if (action != null) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onAction) { Text(action) }
            }
        }
    }
}

@Composable
private fun ContactAvatar(contact: Contact, name: String, size: androidx.compose.ui.unit.Dp) {
    val color = contactAvatarColors[contactColorIndex(contact.login, contactAvatarColors.size)]
    Surface(
        modifier = Modifier.size(size),
        shape = CircleShape,
        color = color,
        border = BorderStroke(2.dp, BrandGold.copy(alpha = 0.42f)),
        shadowElevation = 8.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                contactInitial(name, contact.login),
                color = Color(0xFFF6E8C0),
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun RenameContactDialog(
    contact: Contact,
    saving: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onRename: (customName: String?) -> Unit,
    onErrorCleared: () -> Unit,
) {
    var value by rememberSaveable(contact.login) { mutableStateOf(contact.displayName) }
    val trimmed = value.trim()

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("Изменить имя") },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = {
                        value = it
                        if (errorMessage != null) onErrorCleared()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Имя контакта") },
                    singleLine = true,
                    enabled = !saving,
                    isError = value.isBlank() || errorMessage != null,
                    supportingText = {
                        when {
                            value.isBlank() -> Text("Введите имя")
                            errorMessage != null -> Text(errorMessage.orEmpty())
                        }
                    },
                )
                if (contact.customName != null) {
                    TextButton(
                        onClick = { onRename(null) },
                        enabled = !saving,
                        modifier = Modifier.align(Alignment.Start),
                    ) {
                        Text("Вернуть исходное имя")
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onRename(trimmed) }, enabled = trimmed.isNotEmpty() && !saving) {
                if (saving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text("Сохранить")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text("Отмена") }
        },
    )
}
