package org.tinitalk.data

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

data class Profile(val login: String, @SerializedName("display_name") val displayName: String)
data class Contact(val login: String, @SerializedName("display_name") val displayName: String)
data class CallHistoryItem(
    val id: Long,
    @SerializedName("peer_login") val peerLogin: String,
    @SerializedName("peer_name") val peerName: String,
    val direction: String,
    val outcome: String,
    @SerializedName("started_at") val startedAt: Long,
    @SerializedName("duration_seconds") val durationSeconds: Long,
)
data class CallHistoryPage(
    val items: List<CallHistoryItem>,
    @SerializedName("next_before") val nextBefore: Long,
    @SerializedName("latest_id") val latestId: Long,
    @SerializedName("unread_missed_count") val unreadMissedCount: Int,
)

class ApiException(val code: Int, message: String) : RuntimeException(message)

interface HouseholdApi {
    fun me(): Profile
    fun contacts(): List<Contact>
    fun calls(limit: Int = 50, before: Long = 0): CallHistoryPage
    fun markCallsRead(throughId: Long)
    fun putDevice(deviceId: String, fcmToken: String)
}

class UrlConnectionApiClient(
    private val baseUrl: String,
    private val login: String,
    private val token: String,
) : HouseholdApi {
    override fun me(): Profile =
        get("/api/me", Profile::class.java)

    override fun contacts(): List<Contact> =
        get("/api/contacts", Array<Contact>::class.java).toList()

    override fun calls(limit: Int, before: Long): CallHistoryPage =
        get("/api/calls?limit=$limit&before=$before", CallHistoryPage::class.java)

    override fun markCallsRead(throughId: Long) {
        put("/api/calls/read", mapOf("through_id" to throughId))
    }

    override fun putDevice(deviceId: String, fcmToken: String) {
        put("/api/device", mapOf("device_id" to deviceId, "fcm_token" to fcmToken))
    }

    private fun put(path: String, value: Any) {
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
    }

    private fun <T> get(path: String, type: Class<T>): T {
        val connection = (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5000
            readTimeout = 5000
            setRequestProperty("Authorization", basicAuth())
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

    private companion object {
        val gson = Gson()
    }
}
