package org.tinitalk.call

import android.content.Context
import androidx.core.content.edit
import org.json.JSONObject
import org.tinitalk.data.AccountId
import org.tinitalk.data.ContactAddress

data class CallReplyResult(
    val key: AccountCallKey,
    val peer: CallPeer,
    val code: CallReplyCode,
    val sessionBinding: CallSessionBinding? = null,
)

internal fun callReplyResult(
    state: CallUiState,
    eventType: String,
    eventKey: AccountCallKey,
    wireCode: String?,
): CallReplyResult? {
    if (eventType != "call.reject" || state.callKey != eventKey || state.direction != CallDirection.Outgoing ||
        state.phase !in setOf(CallPhase.Connecting, CallPhase.Ringing)) return null
    val code = CallReplyCode.fromWire(wireCode) ?: return null
    return CallReplyResult(eventKey, state.peer ?: return null, code)
}

/** Presentation survives the terminal service cleanup; it never owns live call resources. */
internal class CallReplyResultStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("call_reply_result", Context.MODE_PRIVATE)

    fun save(result: CallReplyResult) {
        val json = JSONObject().apply {
            put("account", result.key.accountId.value)
            put("call", result.key.callId)
            put("name", result.peer.displayName)
            put("login", result.peer.login)
            put("server", result.peer.contactAddress?.serverUrl)
            put("reply_code", result.code.wireValue)
            result.sessionBinding?.let {
                put("binding_server", it.serverUrl)
                put("binding_login", it.login)
                put("binding_session", it.sessionId)
                put("binding_config", it.configId)
            }
        }
        preferences.edit { putString("result", json.toString()) }
    }

    fun load(): CallReplyResult? = runCatching {
        val json = JSONObject(preferences.getString("result", null) ?: return null)
        val key = AccountCallKey(AccountId(json.getString("account")), json.getString("call"))
        val login = json.optString("login").takeIf(String::isNotBlank)
        val server = json.optString("server").takeIf(String::isNotBlank)
        val binding = if (json.has("binding_server")) CallSessionBinding(
            json.getString("binding_server"), json.getString("binding_login"),
            json.optString("binding_session").takeIf(String::isNotBlank), json.optString("binding_config").takeIf(String::isNotBlank),
        ) else null
        CallReplyResult(key, CallPeer(json.getString("name"), login,
            if (server != null && login != null) ContactAddress.of(server, login) else null),
            CallReplyCode.fromWire(json.getString("reply_code")) ?: return null, binding)
    }.getOrNull()

    fun clear(key: AccountCallKey? = null) {
        if (key == null || load()?.key == key) preferences.edit { remove("result") }
    }

    fun clearForNewCall(key: AccountCallKey) {
        if (load()?.key != key) clear()
    }

    fun clearForSession(accountId: AccountId?, binding: CallSessionBinding) {
        val current = load() ?: return
        if ((accountId == null || current.key.accountId == accountId) && current.sessionBinding == binding) clear(current.key)
    }
}
