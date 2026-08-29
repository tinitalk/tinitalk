package org.tinitalk.data.signal

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName

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
        if (type == "rtc.video") {
            val enabled = payload["enabled"]
            require(enabled != null && enabled.isJsonPrimitive && enabled.asJsonPrimitive.isBoolean) {
                "rtc.video enabled must be a boolean"
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
            "rtc.restart",
            "rtc.restart.request",
        )

        fun decode(raw: String): SignalEvent {
            require(raw.toByteArray(Charsets.UTF_8).size <= MAX_EVENT_BYTES) { "event too large" }
            return gson.fromJson(raw, SignalEvent::class.java).also { it.validate() }
        }
    }
}

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
