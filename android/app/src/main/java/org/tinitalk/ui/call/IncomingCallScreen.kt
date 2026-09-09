package org.tinitalk.ui.call

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.tinitalk.data.ContactAddress
import org.tinitalk.call.CallReplyCode
import org.tinitalk.ui.theme.CallAnswerGreen
import org.tinitalk.ui.theme.CallRejectRed

@Composable
fun IncomingCallScreen(
    callId: String,
    caller: String,
    contactAddress: ContactAddress? = null,
    fallbackLogin: String = caller,
    replySupported: Boolean = false,
    onReply: (CallReplyCode) -> Unit = {},
    onReplySheetExpanded: () -> Unit = {},
    onAnswer: () -> Unit,
    onReject: () -> Unit,
) {
    var actionLocked by remember(callId) { mutableStateOf(false) }

    fun runOnce(action: () -> Unit) {
        if (actionLocked) return
        actionLocked = true
        action()
    }

    IncomingReplySheet(
        callId = callId,
        supported = replySupported,
        enabled = !actionLocked,
        onReply = { reply -> runOnce { onReply(reply) } },
        onExpanded = onReplySheetExpanded,
    ) { sheetBlocked, handleHeight ->
        val pulseProgress = key(callId) {
            rememberIncomingCallPulse(enabled = !actionLocked && !sheetBlocked)
        }
        CallScreenSurface(
            status = "Входящий звонок",
            peerName = caller,
            contactAddress = contactAddress,
            fallbackLogin = fallbackLogin,
            prominentAvatar = true,
            keepFooterVisible = true,
        ) {
            key(callId) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    SlideCallAction(
                        label = "Ответить",
                        color = CallAnswerGreen,
                        enabled = !actionLocked && !sheetBlocked,
                        pulseProgress = pulseProgress,
                        iconRotation = 0f,
                        onCommit = { runOnce(onAnswer) },
                    )
                    SlideCallAction(
                        label = "Отклонить",
                        color = CallRejectRed,
                        enabled = !actionLocked && !sheetBlocked,
                        pulseProgress = pulseProgress,
                        iconRotation = 135f,
                        onCommit = { runOnce(onReject) },
                    )
                }
            }
            if (replySupported) Spacer(Modifier.height(handleHeight + 40.dp))
        }
    }
}
