package org.tinitalk.ui

import org.tinitalk.call.CallUiState

data class ContactCallAction(
    val label: String,
    val enabled: Boolean,
    val opensCurrentCall: Boolean,
)

fun contactCallAction(
    contactLogin: String,
    ongoingCall: CallUiState?,
    internetAvailable: Boolean = true,
): ContactCallAction = when {
    ongoingCall == null && !internetAvailable ->
        ContactCallAction("Нет подключения", enabled = false, opensCurrentCall = false)
    ongoingCall == null -> ContactCallAction("Позвонить", enabled = true, opensCurrentCall = false)
    ongoingCall.peer?.login == contactLogin ->
        ContactCallAction("Вернуться к звонку", enabled = true, opensCurrentCall = true)
    else -> ContactCallAction(
        "Сначала завершите текущий звонок",
        enabled = false,
        opensCurrentCall = false,
    )
}
