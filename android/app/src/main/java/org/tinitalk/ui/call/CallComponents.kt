package org.tinitalk.ui.call

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import org.tinitalk.R
import org.tinitalk.data.ContactAddress
import org.tinitalk.ui.ContactAvatar
import org.tinitalk.ui.landscapeLayout
import org.tinitalk.ui.landscapeIdentityPane
import org.tinitalk.ui.LandscapeIdentityPaneWeight
import org.tinitalk.ui.theme.CallBackgroundBottom
import org.tinitalk.ui.theme.CallBackgroundTop

internal val LocalCallActionLabelsVisible = staticCompositionLocalOf { true }

internal fun prominentCallAvatarSize(fontScale: Float): Dp =
    if (fontScale >= 1.5f) 168.dp else 224.dp

/** Video is centered in the entire viewport, including the area behind the control rail. */
@Composable
internal fun Modifier.videoCallHeaderInsets(
    landscape: Boolean,
    safeInsets: WindowInsets = WindowInsets.safeDrawing,
): Modifier {
    if (!landscape) return statusBarsPadding()
    val direction = LocalLayoutDirection.current
    val sidePadding = with(LocalDensity.current) {
        maxOf(safeInsets.getLeft(this, direction),
            safeInsets.getRight(this, direction) + LandscapeCallControlsWidth.roundToPx()).toDp()
    }
    // Equal clearance on both sides avoids shifting the text away from the video's center.
    return padding(horizontal = sidePadding).windowInsetsPadding(safeInsets.only(WindowInsetsSides.Top))
}

/** Keep the same stacked identity and status badge in both video orientations. */
@Composable
internal fun VideoCallHeader(
    status: String,
    peerName: String,
    durationText: String,
    statusColor: Color,
) {
    Column(
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = status,
            modifier = Modifier
                .clip(RoundedCornerShape(18.dp))
                .background(Color.Black.copy(alpha = 0.32f))
                .padding(horizontal = 14.dp, vertical = 6.dp),
            color = statusColor,
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = peerName,
            color = Color.White,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = durationText,
            color = Color.White.copy(alpha = 0.82f),
            style = MaterialTheme.typography.titleSmall,
        )
    }
}

@Composable
internal fun CallScreenSurface(
    status: String,
    peerName: String,
    contactAddress: ContactAddress? = null,
    fallbackLogin: String = peerName,
    detail: String? = null,
    detailAccessory: (@Composable () -> Unit)? = null,
    statusColor: Color = Color.White.copy(alpha = 0.76f),
    statusAccessory: (@Composable () -> Unit)? = null,
    pulsingAvatar: Boolean = false,
    prominentAvatar: Boolean = false,
    keepFooterVisible: Boolean = false,
    scrollable: Boolean = false,
    landscapeControls: (@Composable () -> Unit)? = null,
    landscapeHasActions: Boolean = true,
    landscapeStatusDetail: (@Composable () -> Unit)? = null,
    footer: @Composable ColumnScope.() -> Unit,
) {
    val landscape = landscapeLayout()
    val compact = LocalDensity.current.fontScale >= 1.5f
    val avatarSize = if (prominentAvatar) {
        prominentCallAvatarSize(LocalDensity.current.fontScale)
    } else {
        if (compact) 88.dp else 120.dp
    }
    val verticalPadding = if (compact) 12.dp else 24.dp
    val headerSpacing = if (compact) 28.dp else 44.dp
    val transition = rememberInfiniteTransition(label = "callerPulse")
    val avatarScale = if (pulsingAvatar) {
        val scale by transition.animateFloat(
            initialValue = 0.97f,
            targetValue = 1.04f,
            animationSpec = infiniteRepeatable(
                animation = tween(1_250),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "callerAvatarScale",
        )
        scale
    } else {
        1f
    }

    val statusHeader: @Composable () -> Unit = {
        Text(
            text = status,
            color = statusColor,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(headerSpacing),
            contentAlignment = Alignment.Center,
        ) {
            statusAccessory?.invoke()
        }
    }
    val avatar: @Composable (Dp) -> Unit = { fittedAvatarSize ->
        Box(
            modifier = Modifier
                .size(fittedAvatarSize)
                .testTag("call-peer-avatar")
                .graphicsLayer(scaleX = avatarScale, scaleY = avatarScale),
        ) {
            ContactAvatar(
                address = contactAddress,
                displayName = peerName,
                fallbackLogin = fallbackLogin,
                size = fittedAvatarSize,
                borderWidth = 0.dp,
            )
        }
    }
    val identity: @Composable () -> Unit = {
        Text(
            text = peerName,
            color = Color.White,
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (detail != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = detail,
                color = Color.White.copy(alpha = 0.78f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
            )
        }
        detailAccessory?.invoke()
    }
    val header: @Composable (Dp) -> Unit = { fittedAvatarSize ->
        statusHeader()
        avatar(fittedAvatarSize)
        Spacer(Modifier.height(if (compact) 12.dp else 20.dp))
        identity()
    }
    val landscapeStatus: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().testTag("landscape-call-status"),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(status, color = statusColor,
                style = if (landscapeHasActions) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineSmall,
                fontWeight = if (landscapeHasActions) FontWeight.Medium else FontWeight.SemiBold,
                textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
            // Keep this slot during dialing as well, so discovering the route does not move the buttons.
            if (landscapeHasActions || statusAccessory != null) {
                Box(Modifier.fillMaxWidth().height(26.dp), contentAlignment = Alignment.Center) {
                    statusAccessory?.invoke()
                }
            }
            landscapeStatusDetail?.invoke()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(CallBackgroundTop, CallBackgroundBottom))),
    ) {
        if (landscape) {
            Row(
                Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                Column(Modifier.weight(LandscapeIdentityPaneWeight).fillMaxSize()
                    .testTag("landscape-identity-panel").landscapeIdentityPane()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center) {
                        // Keep the photo centered in the remaining space, with a bounded diameter.
                        // Include the pulse's maximum extent in both the cap and available space.
                        val fittedAvatar = minOf(180.dp, maxWidth, maxHeight) / if (pulsingAvatar) 1.04f else 1f
                        if (fittedAvatar > 0.dp) avatar(fittedAvatar)
                    }
                    Spacer(Modifier.height(8.dp))
                    identity()
                }
                Box(Modifier.weight(1f - LandscapeIdentityPaneWeight).fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.End))
                    .padding(horizontal = 24.dp, vertical = 8.dp)
                    .testTag("landscape-call-info"), contentAlignment = Alignment.Center) {
                    if (!landscapeHasActions) {
                        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                            landscapeStatus()
                        }
                    } else Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                        landscapeStatus()
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            if (landscapeControls != null) landscapeControls()
                            else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Bottom,
                                content = footer)
                        }
                    }
                }
            }
        } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = verticalPadding)
                .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (keepFooterVisible) {
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val textHeight = with(LocalDensity.current) {
                        MaterialTheme.typography.titleMedium.lineHeight.toDp() * 2 +
                            MaterialTheme.typography.headlineLarge.lineHeight.toDp() * 2
                    }
                    val fittedAvatar = minOf(avatarSize,
                        (maxHeight - headerSpacing - (if (compact) 12.dp else 20.dp) - textHeight)
                            .coerceAtLeast(48.dp))
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        header(fittedAvatar)
                    }
                }
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    footer()
                }
            } else {
                header(avatarSize)
                Spacer(Modifier.weight(1f))
                footer()
            }
        }
        }
    }
}

@Composable
internal fun RoundCallAction(
    label: String,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String = label,
    enabled: Boolean = true,
    iconRotation: Float = 0f,
    iconResource: Int = R.drawable.ic_call,
    buttonSize: Dp = 72.dp,
    iconSize: Dp = when {
        buttonSize >= 88.dp -> 36.dp
        buttonSize == 72.dp -> 31.dp
        else -> 28.dp
    },
    labelMaxLines: Int = 1,
    showLabel: Boolean = true,
) {
    val labelFontSize = (12f / LocalDensity.current.fontScale.coerceAtLeast(1f)).sp
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier
                .size(buttonSize)
                .clip(CircleShape)
                .background(if (enabled) color else color.copy(alpha = 0.42f)),
        ) {
            Icon(
                painter = painterResource(iconResource),
                contentDescription = contentDescription,
                tint = Color.White.copy(alpha = if (enabled) 1f else 0.48f),
                modifier = Modifier
                    .size(iconSize)
                    .graphicsLayer(rotationZ = iconRotation),
            )
        }
        if (showLabel && LocalCallActionLabelsVisible.current) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = label,
                color = Color.White.copy(alpha = if (enabled) 1f else 0.52f),
                fontSize = labelFontSize,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = labelMaxLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
