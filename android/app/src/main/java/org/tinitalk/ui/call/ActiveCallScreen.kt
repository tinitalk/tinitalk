package org.tinitalk.ui.call

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.telecom.CallEndpointCompat
import org.tinitalk.R
import org.tinitalk.call.CallEndReason
import org.tinitalk.call.ConnectionHealth
import org.tinitalk.telecom.AudioEndpoint
import org.tinitalk.ui.theme.CallRejectRed

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActiveCallScreen(
    peerName: String,
    durationText: String,
    muted: Boolean,
    connectionHealth: ConnectionHealth,
    currentEndpoint: AudioEndpoint?,
    availableEndpoints: List<AudioEndpoint>,
    onMute: (Boolean) -> Unit,
    onSelectEndpoint: (AudioEndpoint) -> Unit,
    onEnd: () -> Unit,
) {
    var routePickerVisible by remember { mutableStateOf(false) }
    val status = when (connectionHealth) {
        ConnectionHealth.Connecting -> "Соединяемся…"
        ConnectionHealth.Reconnecting -> "Восстанавливаем связь…"
        ConnectionHealth.Poor -> "Слабая сеть"
        else -> "Идёт разговор"
    }
    val statusColor = if (connectionHealth == ConnectionHealth.Poor || connectionHealth == ConnectionHealth.Reconnecting) {
        Color(0xFFFFCA6A)
    } else {
        Color.White.copy(alpha = 0.76f)
    }

    CallScreenSurface(
        status = status,
        peerName = peerName,
        detail = durationText,
        statusColor = statusColor,
    ) {
        Text(
            text = "Звук: ${audioEndpointLabel(currentEndpoint)}",
            color = Color.White.copy(alpha = 0.68f),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(14.dp))
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
                label = "Завершить",
                modifier = Modifier.weight(1f),
                color = CallRejectRed,
                onClick = onEnd,
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

@Composable
internal fun MuteCallAction(
    muted: Boolean,
    modifier: Modifier = Modifier,
    onMute: (Boolean) -> Unit,
) {
    RoundCallAction(
        label = "Микрофон",
        modifier = modifier,
        contentDescription = if (muted) "Включить микрофон" else "Выключить микрофон",
        color = if (muted) Color(0xFF55708F) else Color(0xFF33465F),
        onClick = { onMute(!muted) },
        iconResource = if (muted) R.drawable.ic_mic_off else R.drawable.ic_mic,
    )
}

@Composable
internal fun AudioRouteAction(
    currentEndpoint: AudioEndpoint?,
    availableEndpoints: List<AudioEndpoint>,
    modifier: Modifier = Modifier,
    onSelectEndpoint: (AudioEndpoint) -> Unit,
    onShowPicker: () -> Unit,
) {
    val directRoute = directAudioRoute(currentEndpoint, availableEndpoints)
    RoundCallAction(
        label = "Звук",
        modifier = modifier,
        contentDescription = when (directRoute?.type) {
            CallEndpointCompat.TYPE_SPEAKER -> "Включить громкую связь"
            CallEndpointCompat.TYPE_EARPIECE -> "Выключить громкую связь"
            else -> "Выбрать устройство звука. Сейчас: ${audioEndpointLabel(currentEndpoint)}"
        },
        color = Color(0xFF33465F),
        enabled = availableEndpoints.isNotEmpty(),
        onClick = {
            if (directRoute != null) {
                onSelectEndpoint(directRoute)
            } else {
                onShowPicker()
            }
        },
        iconResource = audioEndpointIcon(currentEndpoint),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AudioRoutePicker(
    visible: Boolean,
    currentEndpoint: AudioEndpoint?,
    availableEndpoints: List<AudioEndpoint>,
    onDismiss: () -> Unit,
    onSelectEndpoint: (AudioEndpoint) -> Unit,
) {
    if (!visible) return
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            text = "Куда выводить звук",
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        availableEndpoints.forEach { endpoint ->
            val selected = endpoint.id == currentEndpoint?.id
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        onDismiss()
                        onSelectEndpoint(endpoint)
                    }
                    .padding(PaddingValues(horizontal = 24.dp, vertical = 16.dp)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(audioEndpointIcon(endpoint)),
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                )
                Column(modifier = Modifier.weight(1f).padding(start = 18.dp)) {
                    Text(audioEndpointLabel(endpoint), style = MaterialTheme.typography.titleMedium)
                    if (selected) {
                        Text(
                            text = "Используется сейчас",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                if (selected) {
                    Text("✓", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.navigationBarsPadding().height(12.dp))
    }
}

internal fun directAudioRoute(current: AudioEndpoint?, available: List<AudioEndpoint>): AudioEndpoint? {
    val phoneRouteTypes = setOf(CallEndpointCompat.TYPE_EARPIECE, CallEndpointCompat.TYPE_SPEAKER)
    if (available.size != 2 || available.map { it.type }.toSet() != phoneRouteTypes) return null
    val nextType = if (current?.type == CallEndpointCompat.TYPE_SPEAKER) {
        CallEndpointCompat.TYPE_EARPIECE
    } else {
        CallEndpointCompat.TYPE_SPEAKER
    }
    return available.firstOrNull { it.type == nextType }
}

private fun audioEndpointLabel(endpoint: AudioEndpoint?): String = when (endpoint?.type) {
    CallEndpointCompat.TYPE_EARPIECE -> "Телефон"
    CallEndpointCompat.TYPE_SPEAKER -> "Динамик"
    CallEndpointCompat.TYPE_BLUETOOTH -> "Bluetooth"
    CallEndpointCompat.TYPE_WIRED_HEADSET -> "Наушники"
    CallEndpointCompat.TYPE_STREAMING -> "Другое устройство"
    else -> "Устройство"
}

private fun audioEndpointIcon(endpoint: AudioEndpoint?): Int = when (endpoint?.type) {
    CallEndpointCompat.TYPE_BLUETOOTH -> R.drawable.ic_bluetooth
    CallEndpointCompat.TYPE_WIRED_HEADSET -> R.drawable.ic_headset
    CallEndpointCompat.TYPE_SPEAKER -> R.drawable.ic_volume_up
    else -> R.drawable.ic_call
}

@Composable
fun EndedCallScreen(peerName: String, reason: CallEndReason?) {
    CallScreenSurface(status = if (reason == CallEndReason.Busy) "Занято" else "Звонок завершён", peerName = peerName) {
        Spacer(Modifier.height(18.dp))
    }
}
