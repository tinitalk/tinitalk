package org.tinitalk.ui.call

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.tinitalk.telecom.AudioEndpoint
import org.tinitalk.ui.theme.CallRejectRed

@Composable
fun OutgoingCallScreen(
    callee: String,
    status: String = "Звоним…",
    muted: Boolean,
    currentEndpoint: AudioEndpoint?,
    availableEndpoints: List<AudioEndpoint>,
    onMute: (Boolean) -> Unit,
    onSelectEndpoint: (AudioEndpoint) -> Unit,
    onCancel: () -> Unit,
) {
    var routePickerVisible by remember { mutableStateOf(false) }

    CallScreenSurface(status = status, peerName = callee, pulsingAvatar = true) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            MuteCallAction(
                muted = muted,
                modifier = Modifier.weight(1f),
                onMute = onMute,
            )
            AudioRouteAction(
                currentEndpoint = currentEndpoint,
                availableEndpoints = availableEndpoints,
                modifier = Modifier.weight(1f),
                onSelectEndpoint = onSelectEndpoint,
                onShowPicker = { routePickerVisible = true },
            )
            RoundCallAction(
                label = "Отменить",
                modifier = Modifier.weight(1f),
                color = CallRejectRed,
                onClick = onCancel,
                iconRotation = 135f,
            )
        }
        Spacer(Modifier.height(18.dp))
    }

    AudioRoutePicker(
        visible = routePickerVisible,
        currentEndpoint = currentEndpoint,
        availableEndpoints = availableEndpoints,
        onDismiss = { routePickerVisible = false },
        onSelectEndpoint = onSelectEndpoint,
    )
}
