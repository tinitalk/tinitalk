package org.tinitalk.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.tinitalk.data.CallHistoryItem
import org.tinitalk.ui.theme.BrandGold
import org.tinitalk.ui.theme.CallAnswerGreen
import org.tinitalk.ui.theme.CallRejectRed
import java.time.Instant
import java.time.ZoneId

@Composable
fun HistoryScreen(
    items: List<CallHistoryItem>,
    loaded: Boolean,
    loading: Boolean,
    loadingMore: Boolean,
    nextBefore: Long,
    errorMessage: String?,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
) {
    val now = Instant.now()
    val zone = ZoneId.systemDefault()
    when {
        loading && items.isEmpty() -> HistoryLoading()
        loaded && items.isEmpty() && errorMessage == null -> HistoryEmpty()
        items.isEmpty() && errorMessage != null -> HistoryError(errorMessage, onRetry)
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (errorMessage != null) {
                item(key = "history-error") { HistoryError(errorMessage, onRetry, compact = true) }
            }
            itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
                Column {
                    val day = historyDayLabel(item.startedAt, now, zone)
                    if (index == 0 || day != historyDayLabel(items[index - 1].startedAt, now, zone)) {
                        Text(
                            text = day,
                            modifier = Modifier.padding(start = 4.dp, top = if (index == 0) 4.dp else 14.dp, bottom = 8.dp),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    HistoryRow(item)
                    if (index == items.lastIndex && nextBefore > 0 && !loadingMore) {
                        LaunchedEffect(nextBefore) { onLoadMore() }
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

@Composable
private fun HistoryRow(item: CallHistoryItem) {
    val name = contactDisplayName(item.peerName)
    val missed = item.direction == "incoming" &&
        (item.outcome == "unanswered" || item.outcome == "cancelled_after_ringing")
    val successful = item.outcome == "completed"
    val statusColor = when {
        missed -> CallRejectRed
        successful -> CallAnswerGreen
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "$name, ${historyStatus(item)}, ${historyTime(item.startedAt)}" },
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 78.dp).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(50.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, BrandGold.copy(alpha = 0.28f)),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        contactInitial(name, item.peerLogin),
                        color = Color(0xFFF6E8C0),
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (item.direction == "incoming") "↙" else "↗",
                        color = statusColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        historyStatus(item),
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
private fun HistoryLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(strokeWidth = 3.dp)
    }
}

@Composable
private fun HistoryEmpty() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
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
        Text("История звонков пока пуста", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(7.dp))
        Text(
            "Здесь появятся входящие и исходящие звонки.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HistoryError(message: String, onRetry: () -> Unit, compact: Boolean = false) {
    Column(
        modifier = (if (compact) Modifier.fillMaxWidth() else Modifier.fillMaxSize())
            .padding(if (compact) 12.dp else 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(12.dp))
        Button(onClick = onRetry) { Text("Повторить") }
    }
}
