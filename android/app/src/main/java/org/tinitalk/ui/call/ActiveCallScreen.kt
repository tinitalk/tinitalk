package org.tinitalk.ui.call

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.tinitalk.R
import org.tinitalk.ui.theme.CallRejectRed

@Composable
fun ActiveCallScreen(
    peerName: String,
    muted: Boolean,
    onMute: (Boolean) -> Unit,
    onEnd: () -> Unit,
) {
    CallScreenSurface(status = "Идёт разговор", peerName = peerName) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            RoundCallAction(
                label = if (muted) "Включить микрофон" else "Выключить микрофон",
                color = if (muted) Color(0xFF55708F) else Color(0xFF33465F),
                onClick = { onMute(!muted) },
                iconResource = if (muted) R.drawable.ic_mic_off else R.drawable.ic_mic,
            )
            RoundCallAction(
                label = "Завершить",
                color = CallRejectRed,
                onClick = onEnd,
                iconRotation = 135f,
            )
        }
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
fun EndedCallScreen(peerName: String) {
    CallScreenSurface(status = "Звонок завершён", peerName = peerName) {
        Spacer(Modifier.height(18.dp))
    }
}
