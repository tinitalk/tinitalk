package org.tinitalk.ui

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
        ContactCallAction("Вернуться к звонку", enabled = true, opensCurrentCall = true)
    ongoingCall != null -> ContactCallAction(
        "Сначала завершите текущий звонок",
        enabled = false,
        opensCurrentCall = false,
    )
    !canCall -> ContactCallAction(
        "Позвонить",
        enabled = true,
        opensCurrentCall = false,
        explainsUnavailableContact = true,
    )
    !internetAvailable -> ContactCallAction("Нет подключения", enabled = false, opensCurrentCall = false)
    else -> ContactCallAction("Позвонить", enabled = true, opensCurrentCall = false)
}
