package org.tinitalk.ui.call

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Velocity
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import org.tinitalk.R
import org.tinitalk.call.CallReplyCode

private enum class ReplySheetPosition { Collapsed, Expanded }

/** The handle owns the drag; the answer rows require a subsequent, separate tap. */
@Composable
internal fun IncomingReplySheet(
    callId: String,
    supported: Boolean,
    enabled: Boolean,
    onReply: (CallReplyCode) -> Unit,
    onExpanded: () -> Unit,
    content: @Composable (blocked: Boolean, handleHeight: Dp) -> Unit,
) {
    if (!supported) {
        content(false, 0.dp)
        return
    }
    key(callId) {
        val state = remember { AnchoredDraggableState(ReplySheetPosition.Collapsed) }
        val interactions = remember { MutableInteractionSource() }
        val dragging by interactions.collectIsDraggedAsState()
        val scope = rememberCoroutineScope()
        val density = LocalDensity.current
        val bodyScroll = rememberScrollState()
        val bodyDragging by bodyScroll.interactionSource.collectIsDraggedAsState()
        var bodyHeight by remember { mutableIntStateOf(0) }
        var handleHeight by remember { mutableIntStateOf(0) }
        var expandedReported by remember { mutableStateOf(false) }
        val currentOnExpanded by rememberUpdatedState(onExpanded)
        val currentEnabled by rememberUpdatedState(enabled)
        val offset = state.offset.takeUnless { it.isNaN() } ?: bodyHeight.toFloat()
        val renderedOffset = offset.roundToInt()
        val progress = if (bodyHeight > 0) (1f - offset / bodyHeight).coerceIn(0f, 1f) else 0f
        // Match the rendered position: a subpixel settling tail must not leave
        // an invisible scrim over the call buttons once the sheet has closed.
        val opening = state.isAnimationRunning && state.targetValue == ReplySheetPosition.Expanded
        val blocked = dragging || bodyDragging || opening || renderedOffset < bodyHeight
        val fullyOpen = bodyHeight > 0 && offset <= 0.5f &&
            state.settledValue == ReplySheetPosition.Expanded && !dragging && !bodyDragging && !state.isAnimationRunning
        val bodyDragConnection = remember(state, bodyScroll, density) {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (source != NestedScrollSource.UserInput || !currentEnabled) return Offset.Zero
                    val draggingSheet = state.offset > 0f || (available.y > 0f && bodyScroll.value == 0)
                    return if (draggingSheet) Offset(0f, state.dispatchRawDelta(available.y)) else Offset.Zero
                }

                override suspend fun onPreFling(available: Velocity): Velocity {
                    if (state.offset <= 0f) return Velocity.Zero
                    val travel = state.anchors.positionOf(ReplySheetPosition.Collapsed)
                    val fastDown = available.y > with(density) { 125.dp.toPx() }
                    state.animateTo(if (fastDown || state.offset >= travel / 2f) {
                        ReplySheetPosition.Collapsed
                    } else ReplySheetPosition.Expanded)
                    return Velocity(0f, available.y)
                }
            }
        }
        fun close() { scope.launch { state.animateTo(ReplySheetPosition.Collapsed) } }
        BackHandler(blocked) { close() }
        LaunchedEffect(fullyOpen) {
            if (fullyOpen && !expandedReported) {
                expandedReported = true
                currentOnExpanded()
            }
        }
        Box(Modifier.fillMaxSize().clipToBounds()) {
            content(blocked, with(density) { handleHeight.toDp() })
            if (blocked) {
                Box(Modifier.fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.65f * progress))
                    .testTag("incoming_reply_scrim")
                    .clickable(onClickLabel = stringResource(R.string.call_reply_close)) { close() })
            }
            BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                .padding(bottom = if (density.fontScale >= 1.5f) 12.dp else 24.dp)
                .clipToBounds()) {
                val maximumBodyHeight = (maxHeight - with(density) { handleHeight.toDp() }).coerceAtLeast(48.dp)
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .offset { IntOffset(0, renderedOffset) }) {
                    val title = stringResource(R.string.call_reply_sheet_title)
                    val actionLabel = stringResource(if (blocked) R.string.call_reply_close else R.string.call_reply_open)
                    Column(Modifier.fillMaxWidth()
                        .testTag("incoming_reply_handle")
                        .onSizeChanged { handleHeight = it.height }
                        .anchoredDraggable(state, Orientation.Vertical, enabled = enabled, interactionSource = interactions)
                        .semantics {
                            role = Role.Button
                            contentDescription = title
                            onClick(actionLabel) {
                                if (enabled) scope.launch {
                                    state.animateTo(if (blocked) ReplySheetPosition.Collapsed else ReplySheetPosition.Expanded)
                                }
                                enabled
                            }
                        }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(Modifier.size(148.dp, 3.dp).background(Color.White.copy(alpha = 0.8f), RoundedCornerShape(2.dp)))
                        Spacer(Modifier.height(10.dp))
                        Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                    }
                    Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                        .heightIn(max = maximumBodyHeight)
                        .onSizeChanged {
                            bodyHeight = it.height
                            state.updateAnchors(DraggableAnchors {
                                ReplySheetPosition.Collapsed at it.height.toFloat()
                                ReplySheetPosition.Expanded at 0f
                            }, state.targetValue)
                        }
                        .then(if (fullyOpen) Modifier else Modifier.clearAndSetSemantics {}),
                        shape = RoundedCornerShape(28.dp),
                        color = MaterialTheme.colorScheme.surface,
                    ) {
                        Column(Modifier.nestedScroll(bodyDragConnection).verticalScroll(bodyScroll).padding(vertical = 8.dp)) {
                            CallReplyCode.entries.forEachIndexed { index, reply ->
                                val text = stringResource(reply.textRes)
                                Text(text, style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.fillMaxWidth()
                                        .testTag("incoming_reply_${reply.wireValue}")
                                        .clickable(enabled = enabled && fullyOpen,
                                            onClickLabel = stringResource(R.string.call_reply_select, text)) { onReply(reply) }
                                        .padding(horizontal = 24.dp, vertical = 20.dp))
                                if (index < CallReplyCode.entries.lastIndex) HorizontalDivider(Modifier.padding(horizontal = 24.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
