package org.tinitalk.ui.call

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.tinitalk.data.ContactAddress
import org.tinitalk.ui.theme.CallAnswerGreen
import org.tinitalk.ui.theme.CallRejectRed

@Composable
fun IncomingCallScreen(
    callId: String,
    caller: String,
    contactAddress: ContactAddress? = null,
    fallbackLogin: String = caller,
    onAnswer: () -> Unit,
    onReject: () -> Unit,
) {
    var actionLocked by remember(callId) { mutableStateOf(false) }

    fun runOnce(action: () -> Unit) {
        if (actionLocked) return
        actionLocked = true
        action()
    }

    CallScreenSurface(
        status = "Входящий звонок",
        peerName = caller,
        contactAddress = contactAddress,
        fallbackLogin = fallbackLogin,
        prominentAvatar = true,
    ) {
        Text(
            text = "Сдвиньте нужную кнопку вверх",
            color = Color.White.copy(alpha = 0.66f),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
        )
        key(callId) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                SlideCallAction(
                    label = "Отклонить",
                    color = CallRejectRed,
                    enabled = !actionLocked,
                    iconRotation = 135f,
                    onCommit = { runOnce(onReject) },
                    modifier = Modifier.weight(1f),
                )
                SlideCallAction(
                    label = "Ответить",
                    color = CallAnswerGreen,
                    enabled = !actionLocked,
                    iconRotation = 0f,
                    onCommit = { runOnce(onAnswer) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
