package org.tinitalk.ui.call

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.tinitalk.ui.theme.CallRejectRed

@Composable
fun OutgoingCallScreen(
    callee: String,
    status: String = "Звоним…",
    onCancel: () -> Unit,
) {
    CallScreenSurface(status = status, peerName = callee, pulsingAvatar = true) {
        RoundCallAction(
            label = "Отменить",
            color = CallRejectRed,
            onClick = onCancel,
            iconRotation = 135f,
        )
        Spacer(Modifier.height(18.dp))
    }
}
