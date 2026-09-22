package org.tinitalk.ui

import org.tinitalk.i18n.appString

import org.tinitalk.R

import org.tinitalk.call.CallUiState

data class ContactCallAction(
    val label: String,
    val enabled: Boolean,
    val opensCurrentCall: Boolean,
    val explainsUnavailableContact: Boolean = false,
)

fun contactCallAction(
    contactLogin: String,
    ongoingCall: CallUiState?,
    internetAvailable: Boolean = true,
    canCall: Boolean = true,
): ContactCallAction = when {
    ongoingCall?.peer?.login == contactLogin ->
        ContactCallAction(appString(R.string.text_return_to_call_206), enabled = true, opensCurrentCall = true)
    ongoingCall != null -> ContactCallAction(
        appString(R.string.text_end_the_current_call_first_207),
        enabled = false,
        opensCurrentCall = false,
    )
    !canCall -> ContactCallAction(
        appString(R.string.text_call_208),
        enabled = true,
        opensCurrentCall = false,
        explainsUnavailableContact = true,
    )
    !internetAvailable -> ContactCallAction(appString(R.string.text_no_connection_58), enabled = false, opensCurrentCall = false)
    else -> ContactCallAction(appString(R.string.text_call_208), enabled = true, opensCurrentCall = false)
}
