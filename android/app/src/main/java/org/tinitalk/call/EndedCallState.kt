package org.tinitalk.call

import android.os.Bundle
import org.tinitalk.data.AccountId
import org.tinitalk.data.ContactAddress

/** Saves only the ended screen's identity and frozen timing, never live call resources. */
internal fun CallUiState.saveEndedState(binding: CallSessionBinding? = null): Bundle? {
    val key = callKey?.takeIf { phase == CallPhase.Ended } ?: return null
    return Bundle().apply {
        putString("account", key.accountId.value)
        putString("call", key.callId)
        putString("direction", direction?.name)
        putString("reason", endReason?.name)
        putString("name", peer?.displayName)
        putString("login", peer?.login)
        putString("server", peer?.contactAddress?.serverUrl)
        putString("contact_login", peer?.contactAddress?.login)
        binding?.let {
            putString("session_server", it.serverUrl)
            putString("session_login", it.login)
            putString("session_id", it.sessionId)
            putString("session_config", it.configId)
        }
        connectedAtElapsedMs?.let { putLong("connected", it) }
        endedAtElapsedMs?.let { putLong("ended", it) }
    }
}

internal fun restoreEndedCallState(saved: Bundle?): CallUiState? = runCatching {
    val state = saved ?: return null
    val account = state.getString("account")?.takeIf(String::isNotBlank) ?: return null
    val call = state.getString("call")?.takeIf(String::isNotBlank) ?: return null
    val login = state.getString("login")
    val server = state.getString("server")
    val contactLogin = state.getString("contact_login")
    CallUiState(
        accountId = AccountId(account),
        callId = call,
        peer = state.getString("name")?.let { name ->
            CallPeer(name, login, if (server != null && contactLogin != null) ContactAddress.of(server, contactLogin) else null)
        },
        direction = CallDirection.entries.firstOrNull { it.name == state.getString("direction") },
        phase = CallPhase.Ended,
        endReason = CallEndReason.entries.firstOrNull { it.name == state.getString("reason") },
        connectedAtElapsedMs = state.takeIf { it.containsKey("connected") }?.getLong("connected"),
        endedAtElapsedMs = state.takeIf { it.containsKey("ended") }?.getLong("ended"),
    )
}.getOrNull()

internal fun restoreEndedCallBinding(saved: Bundle?): CallSessionBinding? {
    val server = saved?.getString("session_server") ?: return null
    val login = saved.getString("session_login") ?: return null
    return CallSessionBinding(server, login, saved.getString("session_id"), saved.getString("session_config"))
}
