package org.tinitalk.ui.call

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
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
import org.tinitalk.ui.theme.CallBackgroundBottom
import org.tinitalk.ui.theme.CallBackgroundTop

internal fun prominentCallAvatarSize(fontScale: Float): Dp =
    if (fontScale >= 1.5f) 168.dp else 224.dp

@Composable
internal fun CallScreenSurface(
    status: String,
    peerName: String,
    contactAddress: ContactAddress? = null,
    fallbackLogin: String = peerName,
    detail: String? = null,
    statusColor: Color = Color.White.copy(alpha = 0.76f),
    pulsingAvatar: Boolean = false,
    prominentAvatar: Boolean = false,
    footer: @Composable ColumnScope.() -> Unit,
) {
    val compact = LocalDensity.current.fontScale >= 1.5f
    val avatarSize = if (prominentAvatar) {
        prominentCallAvatarSize(LocalDensity.current.fontScale)
    } else {
        if (compact) 88.dp else 120.dp
    }
    val verticalPadding = if (compact) 12.dp else 24.dp
    val headerSpacing = if (compact) 12.dp else 28.dp
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(CallBackgroundTop, CallBackgroundBottom))),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = verticalPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = status,
                color = statusColor,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
            Spacer(Modifier.height(headerSpacing))
            Box(
                modifier = Modifier
                    .size(avatarSize)
                    .testTag("call-peer-avatar")
                    .graphicsLayer(scaleX = avatarScale, scaleY = avatarScale),
            ) {
                ContactAvatar(
                    address = contactAddress,
                    displayName = peerName,
                    fallbackLogin = fallbackLogin,
                    size = avatarSize,
                    borderWidth = 0.dp,
                )
            }
            Spacer(Modifier.height(if (compact) 12.dp else 20.dp))
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
            Spacer(Modifier.weight(1f))
            footer()
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
                tint = Color.White,
                modifier = Modifier
                    .size(if (buttonSize == 72.dp) 31.dp else 28.dp)
                    .graphicsLayer(rotationZ = iconRotation),
            )
        }
        if (showLabel) {
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
