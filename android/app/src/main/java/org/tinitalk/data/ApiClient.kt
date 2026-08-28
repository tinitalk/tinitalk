package org.tinitalk.data

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Base64

data class Profile(val login: String, @SerializedName("display_name") val displayName: String)
data class ServerInfo(
    val service: String?,
    val status: String?,
    @SerializedName("api_version") val apiVersion: Int = 0,
    val commit: String? = null,
)
data class Contact(
    val login: String,
    val displayName: String,
    val defaultDisplayName: String = displayName,
    val customName: String? = null,
)
data class ContactPage(
    val items: List<Contact>,
    @SerializedName("next_cursor") val nextCursor: String,
)
private data class ContactWire(
    val login: String,
    @SerializedName("display_name") val displayName: String,
    @SerializedName("default_display_name") val defaultDisplayName: String?,
    @SerializedName("custom_name") val customName: String?,
) {
    fun toContact() = Contact(login, displayName, defaultDisplayName ?: displayName, customName)
}
private data class ContactPageWire(
    val items: List<ContactWire>,
    @SerializedName("next_cursor") val nextCursor: String,
) {
    fun toContactPage() = ContactPage(items.map(ContactWire::toContact), nextCursor)
}
data class CallHistoryItem(
    val id: Long,
    @SerializedName("peer_login") val peerLogin: String,
    @SerializedName("peer_name") val peerName: String,
    val direction: String,
    val outcome: String,
    val reached: Boolean,
    @SerializedName("started_at") val startedAt: Long,
    @SerializedName("duration_seconds") val durationSeconds: Long,
)
data class UnreadMissedContact(
    @SerializedName("peer_login") val peerLogin: String,
    @SerializedName("started_at") val startedAt: Long,
)
data class CallUnreadState(
    @SerializedName("unread_missed_count") val unreadMissedCount: Int,
    @SerializedName("unread_missed") val unreadMissed: List<UnreadMissedContact>,
)
data class CallHistoryPage(
    val items: List<CallHistoryItem>,
    @SerializedName("next_before") val nextBefore: Long,
    @SerializedName("latest_id") val latestId: Long,
    @SerializedName("unread_missed_count") val unreadMissedCount: Int,
    @SerializedName("unread_missed") val unreadMissed: List<UnreadMissedContact> = emptyList(),
)
private data class CallHistoryPageWire(
    val items: List<CallHistoryItem>?,
    @SerializedName("next_before") val nextBefore: Long,
    @SerializedName("latest_id") val latestId: Long,
    @SerializedName("unread_missed_count") val unreadMissedCount: Int,
    @SerializedName("unread_missed") val unreadMissed: List<UnreadMissedContact>?,
) {
    fun toCallHistoryPage() = CallHistoryPage(
        items.orEmpty(),
        nextBefore,
        latestId,
        unreadMissedCount,
        unreadMissed.orEmpty(),
    )
}
private data class CallHistoryReadResult(
    @SerializedName("unread_missed_count") val unreadMissedCount: Int,
    @SerializedName("unread_missed") val unreadMissed: List<UnreadMissedContact>?,
) {
    fun toCallUnreadState() = CallUnreadState(unreadMissedCount, unreadMissed.orEmpty())
}

class ApiException(val code: Int, message: String) : RuntimeException(message)

interface HouseholdApi {
    fun serverInfo(): ServerInfo
    fun me(): Profile
    fun contactsPage(limit: Int = 20, cursor: String = ""): ContactPage
    fun updateContactName(login: String, customName: String?): Contact
    fun calls(limit: Int = 50, before: Long = 0, peerLogin: String? = null): CallHistoryPage
    fun markCallsRead(throughId: Long, peerLogin: String? = null): CallUnreadState
    fun putDevice(deviceId: String, fcmToken: String)
}

class UrlConnectionApiClient(
    private val baseUrl: String,
    private val login: String,
    private val token: String,
) : HouseholdApi {
    override fun serverInfo(): ServerInfo =
        get("/healthz", ServerInfo::class.java, authenticated = false)

    override fun me(): Profile =
        get("/api/me", Profile::class.java)

    override fun contactsPage(limit: Int, cursor: String): ContactPage =
        get(
            "/api/contacts/page?limit=$limit" +
                cursor.takeIf(String::isNotEmpty)?.let { "&cursor=${encode(it)}" }.orEmpty(),
            ContactPageWire::class.java,
        ).toContactPage()

    override fun updateContactName(login: String, customName: String?): Contact =
        put(
            "/api/contacts/${encode(login)}/name",
            mapOf("custom_name" to customName),
            ContactWire::class.java,
        ).toContact()

    override fun calls(limit: Int, before: Long, peerLogin: String?): CallHistoryPage =
        get(
            "/api/calls?limit=$limit&before=$before" +
                (peerLogin?.let { "&peer=${encode(it)}" } ?: ""),
            CallHistoryPageWire::class.java,
        ).toCallHistoryPage()

    override fun markCallsRead(throughId: Long, peerLogin: String?): CallUnreadState {
        val request = linkedMapOf<String, Any>("through_id" to throughId)
        peerLogin?.let { request["peer_login"] = it }
        return put("/api/calls/read", request, CallHistoryReadResult::class.java).toCallUnreadState()
    }

    override fun putDevice(deviceId: String, fcmToken: String) {
        put<Unit>("/api/device", mapOf("device_id" to deviceId, "fcm_token" to fcmToken), null)
    }

    private fun <T> put(path: String, value: Any, type: Class<T>?): T {
        val body = gson.toJson(value).toByteArray(Charsets.UTF_8)
        val connection = (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            connectTimeout = 5000
            readTimeout = 5000
            doOutput = true
            setRequestProperty("Authorization", basicAuth())
            setRequestProperty("Content-Type", "application/json")
            outputStream.use { it.write(body) }
        }
        val code = connection.responseCode
        if (code !in 200..299) {
            throw ApiException(code, connection.errorStream?.bufferedReader()?.readText() ?: "request failed")
        }
        if (type == null) {
            @Suppress("UNCHECKED_CAST")
            return Unit as T
        }
        return gson.fromJson(connection.inputStream.bufferedReader().readText(), type)
    }

    private fun <T> get(path: String, type: Class<T>, authenticated: Boolean = true): T {
        val connection = (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5000
            readTimeout = 5000
            if (authenticated) setRequestProperty("Authorization", basicAuth())
        }
        val code = connection.responseCode
        if (code !in 200..299) {
            throw ApiException(code, connection.errorStream?.bufferedReader()?.readText() ?: "request failed")
        }
        return gson.fromJson(connection.inputStream.bufferedReader().readText(), type)
    }

    private fun basicAuth(): String {
        val raw = "$login:$token".toByteArray(Charsets.UTF_8)
        return "Basic " + Base64.getEncoder().encodeToString(raw)
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private companion object {
        val gson: Gson = GsonBuilder().serializeNulls().create()
    }
}
