package org.tinitalk.ui.call

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.ui.unit.dp
import org.tinitalk.R

internal const val SlideCommitThreshold = 0.68f

internal data class SlideCallActionState(
    val progress: Float = 0f,
    val locked: Boolean = false,
) {
    fun dragBy(deltaProgress: Float): SlideCallActionState =
        if (locked) this else copy(progress = (progress + deltaProgress).coerceIn(0f, 1f))

    fun release(threshold: Float = SlideCommitThreshold): SlideCallActionResult = when {
        locked -> SlideCallActionResult(this, committed = false)
        progress >= threshold -> SlideCallActionResult(copy(progress = 1f, locked = true), committed = true)
        else -> SlideCallActionResult(copy(progress = 0f), committed = false)
    }
}

internal data class SlideCallActionResult(
    val state: SlideCallActionState,
    val committed: Boolean,
)

@Composable
internal fun SlideCallAction(
    label: String,
    color: Color,
    enabled: Boolean,
    pulseProgress: State<Float>,
    iconRotation: Float,
    onCommit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val travel = 104.dp
    val travelPx = with(LocalDensity.current) { travel.toPx() }
    val haptics = LocalHapticFeedback.current
    var state by remember { mutableStateOf(SlideCallActionState()) }
    var dragging by remember { mutableStateOf(false) }
    val pulseVisibility = animateFloatAsState(
        targetValue = if (enabled && !dragging && !state.locked && state.progress == 0f) 1f else 0f,
        animationSpec = tween(120),
        label = "incomingCallPulseVisibility",
    )
    val renderedProgress by animateFloatAsState(
        targetValue = state.progress,
        animationSpec = if (dragging) {
            snap()
        } else {
            spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMediumLow,
            )
        },
        label = "slideCallAction",
    )

    fun commit() {
        if (!enabled || state.locked) return
        val result = state.dragBy(1f).release()
        state = result.state
        if (result.committed) onCommit()
    }

    val dragState = rememberDraggableState { delta ->
        if (!enabled || state.locked) return@rememberDraggableState
        val wasBelowThreshold = state.progress < SlideCommitThreshold
        state = state.dragBy(-delta / travelPx)
        if (wasBelowThreshold && state.progress >= SlideCommitThreshold) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    // Keep the swipe path separate from the reply handle below the buttons.
    Box(
        modifier = modifier.height(travel + 104.dp).widthIn(min = 104.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset(y = (-52).dp)
                .size(width = 3.dp, height = travel)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.12f)),
        )
        Box(
            modifier = Modifier
                .offset { IntOffset(0, (-travel.toPx() * renderedProgress).roundToInt()) }
                .size(104.dp)
                .drawBehind {
                    // Read animation state only during drawing, not composition/layout.
                    val progress = pulseProgress.value.coerceIn(0f, 1f)
                    val fadeIn = (progress / 0.1f).coerceAtMost(1f)
                    // Keep the wave visible as it expands; fade more near the end.
                    val alpha = 0.30f * fadeIn * (1f - progress * progress) * pulseVisibility.value
                    if (alpha > 0f) {
                        drawCircle(
                            color = Color.White.copy(alpha = alpha),
                            radius = (38.dp + 28.dp * progress).toPx(),
                        )
                    }
                }
                // Clip the touch surface, but let the decorative wave extend beyond it.
                .clip(CircleShape)
                .draggable(
                    state = dragState,
                    orientation = Orientation.Vertical,
                    enabled = enabled && !state.locked,
                    onDragStarted = { dragging = true },
                    onDragStopped = {
                        dragging = false
                        val result = state.release()
                        state = result.state
                        if (enabled && result.committed) onCommit()
                    },
                )
                .semantics {
                    role = Role.Button
                    contentDescription = label
                    if (!enabled || state.locked) {
                        disabled()
                    } else {
                        onClick {
                            commit()
                            true
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .clip(CircleShape)
                    .background(color),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_call),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(34.dp).graphicsLayer(rotationZ = iconRotation),
                )
            }
        }
    }
}
