package org.tinitalk.telecom

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

object AudioEndpointStateCodec {
    const val MaxEndpointCount = 8
    const val MaxFieldLength = 128

    fun encode(state: AudioEndpointState): String = JsonObject().apply {
        state.current?.let { add("current", it.toJson()) }
        add("available", JsonArray().apply {
            state.available.take(MaxEndpointCount).forEach { add(it.toJson()) }
        })
    }.toString()

    fun decode(payload: String?): AudioEndpointState? {
        if (payload.isNullOrEmpty()) return null
        return runCatching {
            val root = JsonParser.parseString(payload).takeIf { it.isJsonObject }?.asJsonObject ?: return null
            val available = root.get("available")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
            val current = root.get("current")?.let { element ->
                if (element.isJsonNull) null else element.toAudioEndpoint() ?: return null
            }
            AudioEndpointState(
                current = current,
                available = available.take(MaxEndpointCount).map { it.toAudioEndpoint() ?: return null },
            )
        }.getOrNull()
    }

    private fun AudioEndpoint.toJson() = JsonObject().apply {
        addProperty("id", id.take(MaxFieldLength))
        addProperty("name", name.take(MaxFieldLength))
        addProperty("type", type)
    }

    private fun com.google.gson.JsonElement.toAudioEndpoint(): AudioEndpoint? = runCatching {
        val endpoint = takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val id = endpoint.string("id") ?: return null
        val name = endpoint.string("name") ?: return null
        val type = endpoint.get("type")?.takeIf { it.isJsonPrimitive }?.asInt ?: return null
        AudioEndpoint(id, name, type)
    }.getOrNull()

    private fun JsonObject.string(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive }?.asString?.take(MaxFieldLength)
}
