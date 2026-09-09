package org.tinitalk.ui.call

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.tinitalk.call.CallDirection
import org.tinitalk.call.CallEndReason
import org.tinitalk.call.CallReplyCode
import org.tinitalk.data.ContactAddress

@Composable
fun EndedCallScreen(
    peerName: String,
    reason: CallEndReason?,
    contactAddress: ContactAddress? = null,
    fallbackLogin: String = peerName,
    reply: CallReplyCode? = null,
    direction: CallDirection? = null,
    durationText: String? = null,
) {
    val explanation = when {
        reply != null -> stringResource(reply.resultTextRes)
        direction == CallDirection.Outgoing && durationText == null && reason == CallEndReason.NotInContacts ->
            "Вас ещё не добавили в контакты"
        else -> null
    }
    CallScreenSurface(
        status = if (durationText != null) "Звонок завершён" else when (reason) {
            CallEndReason.Busy -> "Занято"
            CallEndReason.NotInContacts, CallEndReason.Failed, CallEndReason.ConnectionLost ->
                if (direction == CallDirection.Outgoing) "Не удалось связаться" else "Звонок завершён"
            CallEndReason.Rejected -> if (direction == CallDirection.Outgoing) "Звонок отклонён" else "Звонок завершён"
            CallEndReason.TimedOut -> if (direction == CallDirection.Outgoing) "Нет ответа" else "Звонок завершён"
            else -> "Звонок завершён"
        },
        peerName = peerName,
        contactAddress = contactAddress,
        fallbackLogin = fallbackLogin,
        detail = durationText,
        prominentAvatar = true,
        scrollable = true,
        detailAccessory = {
            if (explanation != null) {
                Spacer(Modifier.height(16.dp))
                Text(explanation, color = Color.White,
                    style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().testTag(if (reply != null) "call_reply_result" else "call_end_explanation"))
            }
        },
    ) {
        Spacer(Modifier.height(18.dp))
    }
}
