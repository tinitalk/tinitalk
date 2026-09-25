package org.tinitalk.ui

import org.tinitalk.i18n.appString

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.tinitalk.R
import org.tinitalk.data.AccountHistory
import org.tinitalk.data.AccountPeerKey
import org.tinitalk.data.CallHistoryItem
import org.tinitalk.data.ContactAddress
import org.tinitalk.ui.theme.BrandGold
import org.tinitalk.ui.theme.CallAnswerGreen
import org.tinitalk.ui.theme.CallRejectRed
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.launch

internal fun shouldScrollToNewest(previousFirstKey: String?, currentFirstKey: String?): Boolean =
    previousFirstKey != null && currentFirstKey != null && previousFirstKey != currentFirstKey

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    items: List<AccountHistory>,
    itemKeys: List<String> = items.map { item -> item.id.toString() },
    internetAvailable: Boolean = true,
    loaded: Boolean,
    loading: Boolean,
    loadingMore: Boolean,
    hasMore: Boolean,
    errorMessage: String?,
    unavailableServers: List<String> = emptyList(),
    onLoadMore: () -> Unit,
    onRefresh: () -> Unit,
    onContactSelected: (AccountPeerKey) -> Unit,
) {
    val now = Instant.now()
    val zone = ZoneId.systemDefault()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val showScrollToTop by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 1 }
    }
    var previousFirstKey by remember { mutableStateOf<String?>(null) }
    var unavailableDialogVisible by remember { mutableStateOf(false) }
    LaunchedEffect(unavailableServers) {
        if (unavailableServers.isEmpty()) unavailableDialogVisible = false
    }
    val currentFirstKey = itemKeys.firstOrNull()
    LaunchedEffect(currentFirstKey) {
        if (shouldScrollToNewest(previousFirstKey, currentFirstKey)) {
            listState.animateScrollToItem(0)
        }
        if (currentFirstKey != null) previousFirstKey = currentFirstKey
    }
    Box(modifier = Modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = loading && items.isNotEmpty(),
            onRefresh = { if (internetAvailable) onRefresh() },
            modifier = Modifier.fillMaxSize(),
        ) {
            when {
                !internetAvailable && items.isEmpty() -> HistoryOffline()
                loading && items.isEmpty() -> HistoryLoading()
                loaded && items.isEmpty() && errorMessage == null -> HistoryEmpty()
                items.isEmpty() && errorMessage != null -> HistoryError(errorMessage)
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        top = 10.dp,
                        end = 16.dp,
                        bottom = if (unavailableServers.isEmpty()) 80.dp else 150.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items.forEachIndexed { index, accountHistory ->
                        val item = accountHistory.item
                        val day = historyDayLabel(item.startedAt, now, zone)
                        if (index == 0 || day != historyDayLabel(items[index - 1].startedAt, now, zone)) {
                            stickyHeader(key = "history-day-$day") { headerIndex ->
                                HistoryDayHeader(day, listState, headerIndex)
                            }
                        }
                        item(key = itemKeys.getOrNull(index) ?: accountHistory.id) {
                            HistoryRow(
                                item = item,
                                contactAddress = accountHistory.address,
                                onClick = {
                                    onContactSelected(AccountPeerKey(accountHistory.accountId, item.peerLogin))
                                },
                            )
                            if (shouldLoadMoreHistory(
                                    index = index,
                                    itemCount = items.size,
                                    nextBefore = if (hasMore) 1L else 0L,
                                    loading = loadingMore,
                                    hasError = false,
                                    internetAvailable = internetAvailable,
                                )
                            ) {
                                LaunchedEffect(itemKeys.getOrNull(index), hasMore) { onLoadMore() }
                            }
                        }
                    }
                    if (loadingMore) {
                        item(key = "history-loading-more") {
                            Box(Modifier.fillMaxWidth().padding(18.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(modifier = Modifier.size(26.dp), strokeWidth = 2.dp)
                            }
                        }
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = items.isNotEmpty() && showScrollToTop,
            modifier = Modifier.align(Alignment.BottomCenter)
                .padding(bottom = if (unavailableServers.isEmpty()) 16.dp else 88.dp),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                shadowElevation = 6.dp,
            ) {
                IconButton(
                    onClick = { scope.launch { listState.animateScrollToItem(0) } },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        painterResource(R.drawable.ic_chevron_right),
                        contentDescription = appString(R.string.text_back_to_top_204),
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(26.dp).graphicsLayer { rotationZ = -90f },
                    )
                }
            }
        }
        if (unavailableServers.isNotEmpty()) {
            HistoryIncompleteBanner(
                modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 12.dp, vertical = 10.dp),
                onClick = { unavailableDialogVisible = true },
            )
        }
    }
    if (unavailableDialogVisible) {
        HistoryUnavailableDialog(
            servers = unavailableServers,
            onDismiss = { unavailableDialogVisible = false },
        )
    }
}

@Composable
internal fun HistoryDayHeader(day: String, listState: LazyListState, headerIndex: Int) {
    val pinned by remember(listState, headerIndex) {
        derivedStateOf {
            val layout = listState.layoutInfo
            layout.visibleItemsInfo.any {
                it.index == headerIndex && it.offset <= -layout.beforeContentPadding
            }
        }
    }
    val shape = RoundedCornerShape(50)
    Box(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            day,
            modifier = Modifier
                .clip(shape)
                .background(if (pinned) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.78f) else Color.Transparent)
                .padding(horizontal = 12.dp, vertical = 5.dp),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun HistoryIncompleteBanner(modifier: Modifier = Modifier, onClick: () -> Unit) {
    val background = lerp(MaterialTheme.colorScheme.surface, BrandGold, 0.18f)
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = background,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, BrandGold.copy(alpha = 0.72f)),
        shadowElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_server_incompatible),
                contentDescription = null,
                tint = BrandGold,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                appString(R.string.text_history_partially_loaded_269),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun HistoryUnavailableDialog(servers: List<String>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(R.drawable.ic_server_incompatible),
                    contentDescription = null,
                    tint = BrandGold,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(appString(R.string.text_history_partially_loaded_269))
            }
        },
        text = {
            if (servers.size == 1) {
                Text(appString(R.string.text_could_not_load_history_from_server_value_270, servers[0]))
            } else {
                Column {
                    Text(appString(R.string.text_could_not_load_history_from_servers_271))
                    Spacer(Modifier.height(12.dp))
                    servers.forEach { server ->
                        Text(server, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(appString(R.string.text_close_272)) }
        },
    )
}

@Composable
internal fun HistoryRow(
    item: CallHistoryItem,
    showPeer: Boolean = true,
    contactAddress: ContactAddress? = null,
    onClick: (() -> Unit)? = null,
) {
    val name = contactDisplayName(item.peerName)
    val direction = if (item.direction == "incoming") appString(R.string.text_incoming_273) else appString(R.string.text_outgoing_274)
    val status = historyReplySummaryRes(item)?.let { androidx.compose.ui.res.stringResource(it) }
        ?: historyStatus(item)
    val missed = isMissedIncoming(item)
    val successful = item.outcome == "completed"
    val statusColor = when {
        missed -> CallRejectRed
        successful -> CallAnswerGreen
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val shape = RoundedCornerShape(22.dp)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "${if (showPeer) name else direction}, $status, ${historyTime(item.startedAt)}"
            }
            .then(
                if (onClick == null) Modifier else Modifier
                    .clip(shape)
                    .clickable(onClickLabel = appString(R.string.text_open_contact_275), onClick = onClick),
            ),
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = if (compactLandscape()) 60.dp else if (showPeer) 78.dp else 70.dp)
                .padding(horizontal = 14.dp, vertical = if (compactLandscape()) 8.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showPeer) {
                ContactAvatar(
                    address = contactAddress,
                    displayName = name,
                    fallbackLogin = item.peerLogin,
                    size = if (compactLandscape()) 40.dp else 50.dp,
                    borderWidth = 1.dp,
                )
                Spacer(Modifier.width(14.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (showPeer) name else direction,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HistoryCallIcon(historyCallIcon(item), statusColor)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        status,
                        color = statusColor,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Text(
                historyTime(item.startedAt),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HistoryCallIcon(icon: HistoryCallIcon, color: Color) {
    val missed = icon.mark == HistoryCallMark.Missed
    val directionIcon = when {
        missed && icon.direction == HistoryCallDirection.Incoming -> R.drawable.ic_history_call_missed_incoming
        missed -> R.drawable.ic_history_call_missed_outgoing
        icon.direction == HistoryCallDirection.Incoming -> R.drawable.ic_history_call_incoming
        else -> R.drawable.ic_history_call_outgoing
    }
    val markIcon = when (icon.mark) {
        HistoryCallMark.Busy -> R.drawable.ic_history_mark_busy
        HistoryCallMark.Rejected -> R.drawable.ic_history_mark_rejected
        HistoryCallMark.Failed -> R.drawable.ic_history_mark_failed
        HistoryCallMark.Interrupted -> R.drawable.ic_history_mark_interrupted
        HistoryCallMark.Completed,
        HistoryCallMark.Missed -> null
    }

    Box(modifier = Modifier.size(25.dp)) {
        Icon(
            painter = painterResource(directionIcon),
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(21.dp).align(Alignment.TopStart),
        )
        markIcon?.let {
            Surface(
                modifier = Modifier.size(12.dp).align(Alignment.BottomEnd),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface,
            ) {
                Icon(
                    painter = painterResource(it),
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.padding(1.5.dp),
                )
            }
        }
    }
}

@Composable
private fun HistoryLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(strokeWidth = 3.dp)
    }
}

@Composable
private fun HistoryOffline() {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(appString(R.string.text_no_internet_connection_3), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            appString(R.string.text_history_will_appear_when_the_connection_is_restored_276),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HistoryEmpty() {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text("◷", color = BrandGold, fontSize = 34.sp)
        }
        Spacer(Modifier.height(18.dp))
        Text(appString(R.string.text_no_call_history_yet_277), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(7.dp))
        Text(
            appString(R.string.text_your_incoming_and_outgoing_calls_will_appear_here_278),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HistoryError(
    message: String,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
    }
}
