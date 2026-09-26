package org.tinitalk.ui.call

import org.tinitalk.i18n.appString

import org.tinitalk.R

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
import org.tinitalk.ui.landscapeLayout

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
            appString(R.string.text_you_have_not_been_added_to_contacts_yet_92)
        else -> null
    }
    val preserveIncomingLayout = direction == CallDirection.Incoming && durationText == null
    val explanationContent: @Composable () -> Unit = {
        if (explanation != null) {
            Spacer(Modifier.height(16.dp))
            Text(explanation, color = Color.White,
                style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().testTag(if (reply != null) "call_reply_result" else "call_end_explanation"))
        }
    }
    IncomingReplyLayout { handleHeight ->
        CallScreenSurface(
            status = if (durationText != null) appString(R.string.text_call_ended_93) else when (reason) {
                CallEndReason.Busy -> appString(R.string.text_busy_91)
                CallEndReason.NotInContacts, CallEndReason.Failed, CallEndReason.ConnectionLost ->
                    if (direction == CallDirection.Outgoing) appString(R.string.text_could_not_connect_192) else appString(R.string.text_call_ended_93)
                CallEndReason.Rejected -> if (direction == CallDirection.Outgoing) appString(R.string.text_call_declined_193) else appString(R.string.text_call_ended_93)
                CallEndReason.TimedOut -> if (direction == CallDirection.Outgoing) appString(R.string.text_no_answer_194) else appString(R.string.text_call_ended_93)
                else -> appString(R.string.text_call_ended_93)
            },
            peerName = peerName,
            contactAddress = contactAddress,
            fallbackLogin = fallbackLogin,
            detail = durationText,
            prominentAvatar = true,
            keepFooterVisible = preserveIncomingLayout,
            scrollable = !preserveIncomingLayout,
            detailAccessory = if (landscapeLayout()) null else explanationContent,
            landscapeHasActions = false,
            landscapeStatusDetail = explanationContent,
        ) {
            Spacer(Modifier.height(if (preserveIncomingLayout) IncomingCallActionHeight + handleHeight + 40.dp else 18.dp))
        }
    }
}
