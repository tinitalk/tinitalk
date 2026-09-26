package org.tinitalk.ui

import org.tinitalk.i18n.appString

import org.tinitalk.R

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.tinitalk.data.AccountContact
import org.tinitalk.data.AccountPeerKey
import kotlin.math.roundToInt

@Composable
internal fun FavoriteContactsPager(
    state: PagerState,
    hasFavorites: Boolean,
    content: @Composable (favorites: Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val contentWidth = if (landscapeLayout()) Modifier.widthIn(max = 600.dp) else Modifier
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (hasFavorites) {
            Box(contentWidth.fillMaxWidth()) {
                FavoriteContactTabs(state.currentPage == 0) { favorites ->
                    scope.launch { state.animateScrollToPage(if (favorites) 0 else 1) }
                }
            }
        }
        HorizontalPager(
            state = state,
            userScrollEnabled = hasFavorites,
            modifier = Modifier.weight(1f).fillMaxWidth().testTag("contacts-pager"),
        ) { page ->
            // Gutters belong to the swipe area; only the list itself stays narrow.
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Box(contentWidth.fillMaxSize().testTag("contacts-page-content-$page")) {
                    content(hasFavorites && page == 0)
                }
            }
        }
    }
}

@Composable
internal fun FavoriteContactTabs(favorites: Boolean, onSelect: (Boolean) -> Unit) {
    val indicatorPosition by animateFloatAsState(
        targetValue = if (favorites) 0f else 1f,
        animationSpec = tween(250, easing = FastOutSlowInEasing),
        label = "favoriteTabIndicator",
    )
    val indicatorColor = MaterialTheme.colorScheme.primary
    val layoutDirection = LocalLayoutDirection.current
    Row(Modifier.fillMaxWidth().selectableGroup().drawWithContent {
        drawContent()
        val position = if (layoutDirection == LayoutDirection.Rtl) 1f - indicatorPosition else indicatorPosition
        val center = size.width * (0.25f + 0.5f * position)
        val y = size.height - 1.dp.toPx()
        drawLine(
            color = indicatorColor,
            start = Offset(center - 16.dp.toPx(), y),
            end = Offset(center + 16.dp.toPx(), y),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }) {
        listOf(appString(R.string.text_favorites_247), appString(R.string.text_all_248)).forEachIndexed { index, label ->
            val selected = favorites == (index == 0)
            Box(
                modifier = Modifier.weight(1f).heightIn(min = 36.dp).selectable(
                    selected = selected,
                    role = Role.Tab,
                    onClick = { onSelect(index == 0) },
                ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The drag changes a local preview; only releasing the finger saves the new order. */
@Composable
internal fun ReorderableFavoriteContacts(
    contacts: List<AccountContact>,
    listState: LazyListState,
    onReorder: (List<AccountPeerKey>) -> Unit,
    row: @Composable (AccountContact, Modifier) -> Unit,
) {
    val sourceKeys = contacts.map { it.peerKey }
    var order by remember { mutableStateOf(sourceKeys) }
    var dragged by remember { mutableStateOf<AccountPeerKey?>(null) }
    var dragTop by remember { mutableFloatStateOf(0f) }
    var dragHeight by remember { mutableIntStateOf(0) }
    var pointerY by remember { mutableFloatStateOf(0f) }
    val byKey = contacts.associateBy { it.peerKey }
    val latestSourceKeys by rememberUpdatedState(sourceKeys)
    val saveOrder by rememberUpdatedState(onReorder)
    val haptics = LocalHapticFeedback.current
    val edge = with(LocalDensity.current) { 64.dp.toPx() }
    val maxSpeed = with(LocalDensity.current) { 720.dp.toPx() }

    LaunchedEffect(sourceKeys) {
        // A server refresh/removal during the gesture cancels its preview, never saves stale contacts.
        dragged = null
        order = sourceKeys
    }
    fun moveOverTarget() {
        val peer = dragged ?: return
        val center = dragTop + dragHeight / 2f - listState.layoutInfo.beforeContentPadding
        val target = listState.layoutInfo.visibleItemsInfo.firstOrNull {
            center >= it.offset && center < it.offset + it.size && it.index < order.size
        } ?: return
        val from = order.indexOf(peer)
        val to = target.index
        if (from < 0 || from == to) return
        if (to > from && center < target.offset + target.size / 2f) return
        if (to < from && center > target.offset + target.size / 2f) return
        listState.requestScrollToItem(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
        order = order.toMutableList().apply { add(to, removeAt(from)) }
    }

    val scrollStrength by remember(listState, edge) {
        derivedStateOf {
            val layout = listState.layoutInfo
            val bottom = (layout.viewportEndOffset + layout.beforeContentPadding).toFloat()
            when {
                dragged == null -> 0f
                pointerY < edge && !listState.canScrollBackward -> 0f
                pointerY > bottom - edge && !listState.canScrollForward -> 0f
                pointerY < edge -> -((edge - pointerY) / edge).coerceIn(0f, 1f)
                pointerY > bottom - edge -> ((pointerY - bottom + edge) / edge).coerceIn(0f, 1f)
                else -> 0f
            }
        }
    }
    LaunchedEffect(dragged, scrollStrength) {
        if (dragged == null || scrollStrength == 0f) return@LaunchedEffect
        var previous = withFrameNanos { it }
        while (isActive) {
            val now = withFrameNanos { it }
            val seconds = ((now - previous) / 1_000_000_000f).coerceAtMost(0.032f)
            previous = now
            val consumed = listState.scrollBy(scrollStrength * maxSpeed * seconds)
            if (consumed == 0f) break
            moveOverTarget()
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            userScrollEnabled = dragged == null,
            modifier = Modifier.fillMaxSize().pointerInput(listState) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { point ->
                        val layout = listState.layoutInfo
                        val y = point.y - layout.beforeContentPadding
                        val hit = layout.visibleItemsInfo.firstOrNull {
                            y >= it.offset && y < it.offset + it.size && it.index < order.size
                        }
                        if (hit != null) {
                            dragged = order[hit.index]
                            dragTop = (hit.offset + layout.beforeContentPadding).toFloat()
                            dragHeight = hit.size
                            pointerY = point.y
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                    },
                    onDrag = { change, delta ->
                        if (dragged != null) {
                            change.consume()
                            pointerY = change.position.y
                            dragTop += delta.y
                            moveOverTarget()
                        }
                    },
                    onDragEnd = {
                        if (dragged != null) saveOrder(order)
                        dragged = null
                    },
                    onDragCancel = {
                        dragged = null
                        listState.requestScrollToItem(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
                        order = latestSourceKeys
                    },
                )
            },
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            itemsIndexed(order, key = { _, key -> accountScopedKey(key.accountId, key.login) }) { index, key ->
                byKey[key]?.let { contact ->
                    val actions = buildList {
                        if (index > 0) add(CustomAccessibilityAction(appString(R.string.text_move_up_249)) {
                            saveOrder(order.toMutableList().apply { add(index - 1, removeAt(index)) }); true
                        })
                        if (index < order.lastIndex) add(CustomAccessibilityAction(appString(R.string.text_move_down_250)) {
                            saveOrder(order.toMutableList().apply { add(index + 1, removeAt(index)) }); true
                        })
                    }
                    row(contact, Modifier.animateItem().graphicsLayer {
                        alpha = if (key == dragged) 0.18f else 1f
                    }.semantics { customActions = actions })
                }
            }
        }
        dragged?.let { key ->
            byKey[key]?.let { contact ->
                Box(Modifier.offset { IntOffset(0, dragTop.roundToInt()) }
                    .padding(horizontal = 16.dp).clearAndSetSemantics {}) {
                    row(contact, Modifier.graphicsLayer {
                        scaleX = 1.025f
                        scaleY = 1.025f
                        shadowElevation = 8.dp.toPx()
                    })
                }
            }
        }
    }
}
