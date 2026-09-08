package org.tinitalk.data.signal

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import java.util.Base64

data class SignalEvent(
    val id: String,
    @SerializedName("call_id") val callId: String,
    val type: String,
    @SerializedName("sent_at") val sentAt: Long,
    val payload: JsonObject,
) {
    fun encode(): String {
        validate()
        val raw = gson.toJson(this)
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_EVENT_BYTES) { "event too large" }
        return raw
    }

    fun validate() {
        require(id.looksLikeUuid()) { "id must be a UUID" }
        require(callId.looksLikeUuid()) { "call_id must be a UUID" }
        require(type in allowedTypes) { "unknown event type" }
        if (type == "rtc.video" || type == "rtc.screen") {
            val enabled = payload["enabled"]
            require(enabled != null && enabled.isJsonPrimitive && enabled.asJsonPrimitive.isBoolean) {
                "rtc.video enabled must be a boolean"
            }
        }
        if (type == "rtc.screen" || type == "rtc.screen.ready") require(payload["share_id"]?.asString?.looksLikeUuid() == true) {
            "rtc.screen share_id must be a UUID"
        }
        if (type == "rtc.sas.commit") require(payload.string("commitment").isBase64Url32()) {
            "commitment must be 32 bytes encoded as unpadded base64url"
        }
        if (type == "rtc.sas.key" || type == "rtc.sas.reveal") {
            require(payload.string("public_key").isBase64Url32()) {
                "public_key must be 32 bytes encoded as unpadded base64url"
            }
            require(payload.string("fingerprint").isLowerHex32()) {
                "fingerprint must be 32 bytes encoded as lowercase hex"
            }
        }
    }

    companion object {
        const val MAX_EVENT_BYTES = 32 * 1024
        const val RING_TIMEOUT_SECONDS = 45
        const val EVENT_BUFFER_LIMIT = 256

        private val gson = Gson()
        private val allowedTypes = setOf(
            "call.start",
            "call.incoming",
            "call.ringing",
            "call.accept",
            "call.connected",
            "call.reject",
            "call.cancel",
            "call.end",
            "call.expire",
            "call.resume",
            "rtc.config",
            "rtc.offer",
            "rtc.answer",
            "rtc.ice",
            "rtc.video",
            "rtc.screen",
            "rtc.screen.ready",
            "rtc.restart",
            "rtc.restart.request",
            "rtc.sas.commit",
            "rtc.sas.key",
            "rtc.sas.reveal",
        )

        fun decode(raw: String): SignalEvent {
            require(raw.toByteArray(Charsets.UTF_8).size <= MAX_EVENT_BYTES) { "event too large" }
            return gson.fromJson(raw, SignalEvent::class.java).also { it.validate() }
        }
    }
}

private fun JsonObject.string(name: String): String =
    get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString.orEmpty()

private fun String.isBase64Url32(): Boolean = runCatching {
    val decoded = Base64.getUrlDecoder().decode(this)
    decoded.size == 32 && Base64.getUrlEncoder().withoutPadding().encodeToString(decoded) == this
}.getOrDefault(false)

private fun String.isLowerHex32(): Boolean =
    length == 64 && all { it in '0'..'9' || it in 'a'..'f' }

private fun String.looksLikeUuid(): Boolean {
    if (length != 36) return false
    return allIndexed { index, char ->
        if (index == 8 || index == 13 || index == 18 || index == 23) {
            char == '-'
        } else {
            char in '0'..'9' || char in 'a'..'f' || char in 'A'..'F'
        }
    }
}

private inline fun String.allIndexed(predicate: (Int, Char) -> Boolean): Boolean {
    for (index in indices) {
        if (!predicate(index, this[index])) return false
    }
    return true
}
