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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.tinitalk.R
import org.tinitalk.ui.contactInitial
import org.tinitalk.ui.theme.CallBackgroundBottom
import org.tinitalk.ui.theme.CallBackgroundTop

@Composable
internal fun CallScreenSurface(
    status: String,
    peerName: String,
    detail: String? = null,
    statusColor: Color = Color.White.copy(alpha = 0.76f),
    pulsingAvatar: Boolean = false,
    footer: @Composable ColumnScope.() -> Unit,
) {
    val compact = LocalDensity.current.fontScale >= 1.5f
    val avatarSize = if (compact) 88.dp else 120.dp
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
                    .graphicsLayer(scaleX = avatarScale, scaleY = avatarScale)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.13f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = contactInitial(peerName, peerName),
                    color = Color.White,
                    fontSize = if (compact) 34.sp else 44.sp,
                    fontWeight = FontWeight.Bold,
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
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(if (enabled) color else color.copy(alpha = 0.42f)),
        ) {
            Icon(
                painter = painterResource(iconResource),
                contentDescription = contentDescription,
                tint = Color.White,
                modifier = Modifier.size(31.dp).graphicsLayer(rotationZ = iconRotation),
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = label,
            color = Color.White.copy(alpha = if (enabled) 1f else 0.52f),
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}
