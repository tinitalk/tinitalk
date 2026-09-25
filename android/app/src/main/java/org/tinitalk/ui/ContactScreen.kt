package org.tinitalk.ui

import org.tinitalk.i18n.appString

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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import org.tinitalk.R
import org.tinitalk.call.CallUiState
import org.tinitalk.data.ContactAddress
import org.tinitalk.data.Contact
import org.tinitalk.ui.theme.CallAnswerGreen
import org.tinitalk.ui.theme.CallRejectRed
import java.time.Instant
import java.time.ZoneId

@Composable
fun ContactScreen(
    contact: Contact,
    contactAddress: ContactAddress,
    photoTarget: ContactPhotoEditTarget? = null,
    photoState: ContactPhotoEditorState = ContactPhotoEditorState(),
    identityKey: String = contact.login,
    accountServerUrl: String? = null,
    internetAvailable: Boolean = true,
    nameUpdate: ContactNameUpdateState,
    history: ContactHistoryState,
    ongoingCall: CallUiState?,
    onBack: () -> Unit,
    onCall: (Contact) -> Unit,
    onOpenCall: () -> Unit,
    onRename: (customName: String) -> Unit,
    onRenameHandled: () -> Unit,
    onLoadMoreHistory: () -> Unit,
    onRetryHistory: () -> Unit,
    onChoosePhotoSource: (ContactPhotoEditTarget, ContactPhotoSource) -> Unit = { _, _ -> },
    onRemovePhoto: (ContactPhotoEditTarget) -> Unit = {},
    removing: Boolean = false,
    removeErrorMessage: String? = null,
    onRemoveContact: () -> Unit = {},
    onRemoveContactDismissed: () -> Unit = {},
    onPinContact: () -> Unit = {},
    shortcutPinned: Boolean? = null,
    onRefreshShortcuts: () -> Unit = {},
    favorite: Boolean = false,
    onToggleFavorite: () -> Unit = {},
) {
    var renameVisible by rememberSaveable(identityKey) { mutableStateOf(false) }
    var photoActionsVisible by rememberSaveable(identityKey) { mutableStateOf(false) }
    var contactMenuVisible by rememberSaveable(identityKey) { mutableStateOf(false) }
    var removeContactVisible by rememberSaveable(identityKey) { mutableStateOf(false) }
    var unavailableCallVisible by rememberSaveable(identityKey) { mutableStateOf(false) }
    val listState = rememberSaveable(identityKey, saver = LazyListState.Saver) { LazyListState() }
    LaunchedEffect(identityKey) { onRefreshShortcuts() }
    LaunchedEffect(contact.canCall) {
        if (contact.canCall != false) unavailableCallVisible = false
    }
    val name = contactDisplayName(contact.displayName)
    val landscape = compactLandscape()
    val action = contactCallAction(
        contact.login,
        ongoingCall,
        internetAvailable,
        canCall = contact.canCall != false,
    )
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
            CollapsingContactLayout(
                name = name,
                address = contactAddress,
                login = contact.login,
                listState = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (landscape) Modifier else Modifier.statusBarsPadding().navigationBarsPadding()),
                landscapeProfile = {
                    Text(contact.login + (accountServerUrl?.let { "@${serverAddress(it)}" } ?: ""),
                        style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 6.dp))
                    Button(onClick = {
                        when {
                            action.opensCurrentCall -> onOpenCall()
                            action.explainsUnavailableContact -> unavailableCallVisible = true
                            else -> onCall(contact)
                        }
                    }, enabled = action.enabled,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (action.explainsUnavailableContact)
                                MaterialTheme.colorScheme.surfaceVariant else CallAnswerGreen,
                            contentColor = Color.White)) {
                        Icon(painterResource(R.drawable.ic_call), null, Modifier.size(24.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(action.label, textAlign = TextAlign.Center)
                    }
                },
                toolbar = { titleModifier ->
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = if (landscape) 48.dp else 64.dp).padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CompositionLocalProvider(LocalRippleConfiguration provides null) {
                            IconButton(
                                onClick = onBack,
                                modifier = Modifier.size(48.dp),
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_arrow_back),
                                    contentDescription = appString(R.string.text_back_101),
                                )
                            }
                        }
                        Spacer(Modifier.width(4.dp))
                        Text(
                            appString(R.string.text_contact_224),
                            modifier = Modifier.weight(1f).then(titleModifier),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        IconButton(onClick = onToggleFavorite, modifier = Modifier.size(48.dp)) {
                            Icon(
                                painterResource(if (favorite) R.drawable.ic_star else R.drawable.ic_star_outline),
                                contentDescription = if (favorite) appString(R.string.text_remove_from_favorites_225) else appString(R.string.text_add_to_favorites_226),
                                tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Box {
                            IconButton(
                                onClick = {
                                    onRefreshShortcuts()
                                    contactMenuVisible = true
                                },
                                modifier = Modifier.size(48.dp),
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_more_vert),
                                    contentDescription = appString(R.string.text_actions_for_value_227, name),
                                )
                            }
                            DropdownMenu(
                                expanded = contactMenuVisible,
                                onDismissRequest = { contactMenuVisible = false },
                                modifier = Modifier.widthIn(min = 260.dp),
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            appString(R.string.text_rename_228),
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_edit),
                                            contentDescription = null,
                                            modifier = Modifier.size(24.dp),
                                        )
                                    },
                                    enabled = internetAvailable,
                                    modifier = Modifier.heightIn(min = 58.dp).testTag("contact-menu-rename"),
                                    contentPadding = PaddingValues(horizontal = 22.dp, vertical = 14.dp),
                                    onClick = {
                                        contactMenuVisible = false
                                        onRenameHandled()
                                        renameVisible = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            appString(R.string.text_change_photo_229),
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_photo_camera),
                                            contentDescription = null,
                                            modifier = Modifier.size(24.dp),
                                        )
                                    },
                                    enabled = photoTarget != null && !photoState.busy,
                                    modifier = Modifier.heightIn(min = 58.dp).testTag("contact-menu-photo"),
                                    contentPadding = PaddingValues(horizontal = 22.dp, vertical = 14.dp),
                                    onClick = {
                                        contactMenuVisible = false
                                        photoActionsVisible = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (shortcutPinned == true) appString(R.string.text_already_on_home_screen_230) else appString(R.string.text_add_to_home_screen_231),
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            painterResource(if (shortcutPinned == true) R.drawable.ic_server_available else R.drawable.ic_add_to_home),
                                            contentDescription = null,
                                            modifier = Modifier.size(24.dp),
                                            tint = if (shortcutPinned == true) CallAnswerGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    },
                                    enabled = !removing,
                                    modifier = Modifier.heightIn(min = 58.dp).testTag("contact-menu-shortcut"),
                                    contentPadding = PaddingValues(horizontal = 22.dp, vertical = 14.dp),
                                    onClick = {
                                        contactMenuVisible = false
                                        onPinContact()
                                    },
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            appString(R.string.text_delete_contact_232),
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = CallRejectRed,
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_delete),
                                            contentDescription = null,
                                            modifier = Modifier.size(24.dp),
                                            tint = CallRejectRed,
                                        )
                                    },
                                    enabled = internetAvailable && !photoState.busy,
                                    modifier = Modifier.heightIn(min = 58.dp).testTag("contact-menu-delete"),
                                    contentPadding = PaddingValues(horizontal = 22.dp, vertical = 14.dp),
                                    onClick = {
                                        contactMenuVisible = false
                                        onRemoveContactDismissed()
                                        removeContactVisible = true
                                    },
                                )
                            }
                        }
                    }
                },
            ) { identityHeight, flingBehavior ->
                LazyColumn(
                    flingBehavior = flingBehavior,
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 20.dp, top = if (landscape) 4.dp else ContactProfileTopPadding, end = 20.dp,
                        bottom = if (history.items.isEmpty()) 22.dp else 84.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (!landscape) item(key = "contact-profile") {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Spacer(Modifier.height(identityHeight))
                            Text(
                                buildAnnotatedString {
                                    withStyle(
                                        SpanStyle(
                                            color = if (accountServerUrl == null) {
                                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            },
                                            fontWeight = if (accountServerUrl == null) {
                                                FontWeight.Light
                                            } else {
                                                FontWeight.SemiBold
                                            },
                                        ),
                                    ) {
                                        append(contact.login)
                                    }
                                    accountServerUrl?.let { serverUrl ->
                                        withStyle(
                                            SpanStyle(
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                                                fontWeight = FontWeight.Light,
                                            ),
                                        ) {
                                            append("@")
                                            append(serverAddress(serverUrl))
                                        }
                                    }
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(34.dp))
                            Button(
                                onClick = {
                                    when {
                                        action.opensCurrentCall -> onOpenCall()
                                        action.explainsUnavailableContact -> unavailableCallVisible = true
                                        else -> onCall(contact)
                                    }
                                },
                                enabled = action.enabled,
                                modifier = Modifier.fillMaxWidth().widthIn(max = 380.dp).heightIn(min = 58.dp),
                                shape = RoundedCornerShape(20.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (action.explainsUnavailableContact) {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    } else {
                                        CallAnswerGreen
                                    },
                                    contentColor = if (action.explainsUnavailableContact) {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    } else {
                                        Color.White
                                    },
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
                    if (!landscape) item(key = "contact-history-title") {
                        Text(
                            appString(R.string.text_call_history_233),
                            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 4.dp, bottom = 4.dp),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    if (history.errorMessage != null) {
                        item(key = "contact-history-error") {
                            ContactHistoryMessage(
                                message = history.errorMessage,
                                action = appString(R.string.text_retry_234).takeIf { internetAvailable },
                                onAction = onRetryHistory,
                                error = true,
                            )
                        }
                    }
                    when {
                        !internetAvailable && history.items.isEmpty() -> item(key = "contact-history-offline") {
                            ContactHistoryMessage(appString(R.string.text_history_will_appear_when_the_connection_is_restored_235))
                        }
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
                                ContactHistoryMessage(appString(R.string.text_no_calls_with_this_contact_yet_236))
                            }
                        else -> history.items.forEachIndexed { index, item ->
                            val day = historyDayLabel(item.startedAt, now, zone)
                            if (index == 0 || day != historyDayLabel(history.items[index - 1].startedAt, now, zone)) {
                                stickyHeader(key = "contact-history-day-$day") { headerIndex ->
                                    HistoryDayHeader(day, listState, headerIndex)
                                }
                            }
                            item(key = "contact-history-${item.id}") {
                                HistoryRow(item, showPeer = false)
                                if (shouldLoadMoreHistory(
                                        index = index,
                                        itemCount = history.items.size,
                                        nextBefore = history.nextBefore,
                                        loading = history.loadingMore,
                                        hasError = history.errorMessage != null,
                                        internetAvailable = internetAvailable,
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

    if (photoActionsVisible && photoTarget != null) {
        ContactPhotoActionSheet(
            hasPhoto = photoState.hasPhoto,
            busy = photoState.busy,
            onGallery = {
                photoActionsVisible = false
                onChoosePhotoSource(photoTarget, ContactPhotoSource.Gallery)
            },
            onFiles = {
                photoActionsVisible = false
                onChoosePhotoSource(photoTarget, ContactPhotoSource.Files)
            },
            onRemove = {
                photoActionsVisible = false
                onRemovePhoto(photoTarget)
            },
            onDismiss = { photoActionsVisible = false },
        )
    }

    if (renameVisible) {
        RenameContactDialog(
            contact = contact,
            identityKey = identityKey,
            internetAvailable = internetAvailable,
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

    if (removeContactVisible) {
        AlertDialog(
            onDismissRequest = {
                if (!removing) {
                    removeContactVisible = false
                    onRemoveContactDismissed()
                }
            },
            title = { Text(appString(R.string.text_delete_contact_237)) },
            text = {
                Column {
                    Text(appString(R.string.text_remove_value_from_your_contacts_238, name))
                    removeErrorMessage?.let { message ->
                        Spacer(Modifier.height(10.dp))
                        Text(message, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = onRemoveContact,
                    enabled = !removing && internetAvailable,
                    colors = ButtonDefaults.buttonColors(containerColor = CallRejectRed),
                ) {
                    if (removing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = Color.White,
                        )
                    } else {
                        Text(appString(R.string.text_delete_239))
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        removeContactVisible = false
                        onRemoveContactDismissed()
                    },
                    enabled = !removing,
                ) { Text(appString(R.string.text_cancel_12)) }
            },
        )
    }

    if (unavailableCallVisible) {
        AlertDialog(
            onDismissRequest = { unavailableCallVisible = false },
            title = { Text(appString(R.string.text_cannot_call_yet_53)) },
            text = {
                Text(
                    appString(R.string.text_you_can_call_once_value_adds_you_to_their_contacts_54, name),
                )
            },
            confirmButton = {
                Button(onClick = { unavailableCallVisible = false }) {
                    Text(appString(R.string.text_ok_61))
                }
            },
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
private fun RenameContactDialog(
    contact: Contact,
    identityKey: String,
    internetAvailable: Boolean,
    saving: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onRename: (customName: String) -> Unit,
    onErrorCleared: () -> Unit,
) {
    var value by rememberSaveable(identityKey) { mutableStateOf(contact.displayName) }
    val trimmed = value.trim()

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(appString(R.string.text_edit_name_242)) },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = {
                        value = it
                        if (errorMessage != null) onErrorCleared()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(appString(R.string.text_contact_name_243)) },
                    singleLine = true,
                    enabled = !saving && internetAvailable,
                    isError = value.isBlank() || errorMessage != null,
                    supportingText = {
                        when {
                            value.isBlank() -> Text(appString(R.string.text_enter_a_name_244))
                            errorMessage != null -> Text(errorMessage.orEmpty())
                        }
                    },
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onRename(trimmed) },
                enabled = trimmed.isNotEmpty() && !saving && internetAvailable,
            ) {
                if (saving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text(appString(R.string.text_save_245))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text(appString(R.string.text_cancel_12)) }
        },
    )
}
