package org.tinitalk.ui

import org.tinitalk.i18n.appString

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateTo
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.calculateTargetValue
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import org.tinitalk.R
import org.tinitalk.data.ContactAddress

internal val ContactProfileTopPadding = 22.dp
private const val CompactNameScale = 20f / 28f

/** A fixed profile placeholder drives the motion without changing the list's scroll geometry. */
@Composable
internal fun CollapsingContactLayout(
    name: String,
    address: ContactAddress,
    login: String,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    toolbar: @Composable (titleModifier: Modifier) -> Unit,
    landscapeProfile: @Composable () -> Unit = {},
    content: @Composable (identityHeight: Dp, flingBehavior: FlingBehavior) -> Unit,
) {
    val scope = rememberCoroutineScope()
    BoxWithConstraints(modifier) {
        if (landscapeLayout()) {
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(LandscapeIdentityPaneWeight).fillMaxSize()
                    .testTag("landscape-identity-panel").landscapeIdentityPane()) {
                    toolbar(Modifier)
                    Column(Modifier.weight(1f).fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(),
                            contentAlignment = Alignment.Center) {
                            val photoSize = minOf(280.dp, maxWidth, maxHeight)
                            if (photoSize > 0.dp) ContactAvatar(
                                address = address, displayName = name, fallbackLogin = login,
                                size = photoSize, borderWidth = 2.dp)
                        }
                        Text(name, Modifier.padding(top = 8.dp),
                            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        landscapeProfile()
                    }
                }
                    Box(Modifier.weight(1f - LandscapeIdentityPaneWeight).fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.End))) {
                        content(0.dp, ScrollableDefaults.flingBehavior())
                        val showUp by remember(listState) {
                            derivedStateOf { listState.firstVisibleItemIndex > 0 }
                        }
                        if (showUp) Surface(
                            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
                            shape = CircleShape, color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            shadowElevation = 6.dp,
                        ) {
                            IconButton(onClick = { scope.launch { listState.animateScrollToItem(0) } }) {
                                Icon(painterResource(R.drawable.ic_chevron_right),
                                    appString(R.string.text_back_to_top_204),
                                    Modifier.size(26.dp).graphicsLayer { rotationZ = -90f })
                            }
                        }
                    }
            }
            return@BoxWithConstraints
        }
        val density = LocalDensity.current
        val measurer = rememberTextMeasurer()
        val expandedStyle = MaterialTheme.typography.headlineMedium.copy(
            fontSize = 28.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
        )
        val compactStyle = expandedStyle.copy(
            fontSize = 20.sp,
            lineHeight = expandedStyle.lineHeight * CompactNameScale,
            textAlign = TextAlign.Start,
        )
        val expandedName = measurer.measure(
            name, expandedStyle, maxLines = 2, overflow = TextOverflow.Ellipsis,
            constraints = Constraints(maxWidth = with(density) { (maxWidth - 64.dp).roundToPx().coerceAtLeast(1) }),
        )
        val compactNameWidth = with(density) { (maxWidth - 216.dp).roundToPx().coerceAtLeast(1) }
        val compactName = measurer.measure(
            name, compactStyle, maxLines = 1, overflow = TextOverflow.Ellipsis,
            constraints = Constraints(maxWidth = compactNameWidth),
        )
        val avatarSize = minOf(208.dp, (maxWidth - 80.dp).coerceAtLeast(40.dp))
        val nameHeight = with(density) { maxOf(56.dp, expandedName.size.height.toDp() + 16.dp) }
        val toolbarHeight = with(density) { maxOf(64.dp, compactName.size.height.toDp() + 16.dp) }
        val identityHeight = avatarSize + 22.dp + nameHeight
        val collapseDistance = with(density) { (ContactProfileTopPadding + identityHeight).toPx() }
        val flingBehavior = rememberContactHeaderFlingBehavior(listState, collapseDistance)
        val progress = remember(listState, collapseDistance) {
            {
                if (listState.firstVisibleItemIndex > 0) 1f
                else (listState.firstVisibleItemScrollOffset / collapseDistance).coerceIn(0f, 1f)
            }
        }
        val collapsed by remember(progress) { derivedStateOf { progress() >= 1f } }
        val compactSemantics by remember(progress) { derivedStateOf { progress() >= 0.8f } }

        Box(Modifier.fillMaxSize().padding(top = toolbarHeight)) { content(identityHeight, flingBehavior) }
        Box(
            Modifier.fillMaxWidth().height(toolbarHeight).background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center,
        ) {
            toolbar(Modifier.graphicsLayer { alpha = (1f - progress() * 3f).coerceIn(0f, 1f) }
                .then(if (compactSemantics) Modifier.clearAndSetSemantics {} else Modifier))
        }

        val widthPx = with(density) { maxWidth.toPx() }
        val photoStartX = with(density) { (maxWidth - avatarSize).toPx() / 2f }
        val photoStartY = with(density) { (toolbarHeight + ContactProfileTopPadding).toPx() }
        val photoEndX = with(density) { 60.dp.toPx() }
        val photoEndY = with(density) { (toolbarHeight - 40.dp).toPx() / 2f }
        val nameStartX = (widthPx - expandedName.size.width) / 2f
        val nameStartY = with(density) {
            (toolbarHeight + ContactProfileTopPadding + avatarSize + 22.dp + nameHeight / 2).toPx()
        } - expandedName.size.height / 2f
        val nameEndX = with(density) { 110.dp.toPx() }
        val nameEndY = with(density) { toolbarHeight.toPx() / 2f } - compactName.size.height / 2f

        // Keep the decoded photo size constant. Only its drawing layer changes while scrolling.
        ContactAvatar(
            address = address,
            displayName = name,
            fallbackLogin = login,
            size = avatarSize,
            borderWidth = 2.dp,
            shadowElevation = 8.dp,
            showRefreshProgress = true,
            modifier = Modifier.graphicsLayer {
                // The photo arrives slightly earlier, leaving room for the moving name.
                val travel = (progress() / 0.85f).coerceIn(0f, 1f)
                val fraction = travel * travel * (3f - 2f * travel)
                transformOrigin = TransformOrigin(0f, 0f)
                scaleX = lerp(1f, 40f / avatarSize.value, fraction)
                scaleY = scaleX
                translationX = lerp(photoStartX, photoEndX, fraction)
                translationY = lerp(photoStartY, photoEndY, fraction)
            }.testTag("contact-profile-avatar"),
        )
        // Both text layouts are measured once; blending handles long names without a line-wrap jump.
        Text(
            name, style = expandedStyle, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(with(density) { expandedName.size.width.toDp() })
                .graphicsLayer {
                    val fraction = progress()
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = lerp(1f, CompactNameScale, fraction)
                    scaleY = scaleX
                    translationX = lerp(nameStartX, nameEndX, fraction)
                    translationY = lerp(nameStartY, nameEndY, fraction)
                    alpha = 1f - ((fraction - 0.65f) / 0.3f).coerceIn(0f, 1f)
                }
                .then(if (compactSemantics) Modifier.clearAndSetSemantics {} else Modifier.semantics {
                    contentDescription = appString(R.string.text_contact_name_value_203, name)
                })
                .drawWithContent {
                    val fraction = progress()
                    val scale = lerp(1f, CompactNameScale, fraction)
                    val right = lerp(size.width, minOf(size.width, compactNameWidth / scale), fraction)
                    clipRect(right = right) { this@drawWithContent.drawContent() }
                },
        )
        Text(
            name, style = compactStyle, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(with(density) { compactName.size.width.toDp() })
                .graphicsLayer {
                    val fraction = progress()
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = lerp(1f / CompactNameScale, 1f, fraction)
                    scaleY = scaleX
                    translationX = lerp(nameStartX, nameEndX, fraction)
                    translationY = lerp(nameStartY, nameEndY, fraction)
                    alpha = ((fraction - 0.65f) / 0.3f).coerceIn(0f, 1f)
                }
                .then(if (!compactSemantics) Modifier.clearAndSetSemantics {} else Modifier.semantics {
                    contentDescription = appString(R.string.text_contact_name_value_203, name)
                }),
        )

        AnimatedVisibility(
            visible = collapsed,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
            enter = fadeIn(tween(160)) + slideInVertically(tween(180)) { it / 3 },
            exit = fadeOut(tween(120)) + slideOutVertically(tween(160)) { it / 3 },
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
    }
}

@Composable
private fun rememberContactHeaderFlingBehavior(
    listState: LazyListState,
    collapseDistance: Float,
): FlingBehavior {
    val defaultFling = ScrollableDefaults.flingBehavior()
    val decay = rememberSplineBasedDecay<Float>()
    return remember(listState, collapseDistance, defaultFling, decay) {
        object : FlingBehavior {
            override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
                val forward = initialVelocity > 0f ||
                    (initialVelocity == 0f && listState.lastScrolledForward)
                val offset = listState.firstVisibleItemScrollOffset.toFloat()
                if (listState.firstVisibleItemIndex != 0 || offset <= 0f || offset >= collapseDistance) {
                    return with(defaultFling) { performFling(initialVelocity) }
                }
                val projectedOffset = decay.calculateTargetValue(offset, initialVelocity)
                if (projectedOffset >= collapseDistance || projectedOffset <= 0f) {
                    return with(defaultFling) { performFling(initialVelocity) }
                }

                val layout = listState.layoutInfo
                val last = layout.visibleItemsInfo.lastOrNull()
                val available = if (last != null && last.index == layout.totalItemsCount - 1) {
                    (last.offset + last.size + layout.afterContentPadding - layout.viewportEndOffset)
                        .coerceAtLeast(0).toFloat()
                } else Float.POSITIVE_INFINITY
                // Short cards cannot reach the collapsed endpoint. Keep normal scrolling
                // instead of snapping back and hiding the content the user just revealed.
                if (available < ceil(collapseDistance) - offset) {
                    return with(defaultFling) { performFling(initialVelocity) }
                }
                val target = if (forward) ceil(collapseDistance) else 0f
                var previous = 0f
                // Continue the gesture immediately, without a separate deceleration and restart.
                // Critical damping settles gently without bouncing past the toolbar.
                // Limit only the snapping velocity so even a near-edge snap cannot overshoot
                // and pull the list backwards. Strong flings use the normal path above.
                val snapVelocity = initialVelocity.coerceIn(-abs(target - offset) * 10f, abs(target - offset) * 10f)
                AnimationState(0f, initialVelocity = snapVelocity).animateTo(
                    target - offset,
                    spring(dampingRatio = 1f, stiffness = 100f),
                ) {
                    val delta = value - previous
                    val consumed = scrollBy(delta)
                    previous = value
                    if (abs(consumed - delta) > 0.5f) cancelAnimation()
                }
                return 0f
            }
        }
    }
}
