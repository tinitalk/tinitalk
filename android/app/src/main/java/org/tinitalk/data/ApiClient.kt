package org.tinitalk.data

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import org.tinitalk.push.WebPushClientConfig
import org.tinitalk.push.WebPushSubscription
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Base64

internal const val SessionIdHeader = "X-TiniTalk-Session-ID"
internal const val AuthReasonHeader = "X-TiniTalk-Auth-Reason"
internal const val SessionReplacedReason = "session_replaced"

data class Profile(val login: String, @SerializedName("display_name") val displayName: String)
data class ServerInfo(
    val service: String?,
    val status: String?,
    @SerializedName("api_version") val apiVersion: Int = 0,
    val commit: String? = null,
    val features: Set<String> = emptySet(),
)
private data class ServerInfoWire(
    val service: String?,
    val status: String?,
    @SerializedName("api_version") val apiVersion: Int,
    val commit: String?,
    val features: List<String>?,
) {
    fun toServerInfo() = ServerInfo(service, status, apiVersion, commit, features.orEmpty().toSet())
}
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

/**
 * Android-side identity wrapper. The server's contact model remains unchanged;
 * an AccountId is required whenever that model leaves the repository boundary.
 */
data class AccountContact(
    val accountId: AccountId,
    val serverUrl: String,
    val contact: Contact,
) {
    val login: String get() = contact.login
    val displayName: String get() = contact.displayName
    val defaultDisplayName: String get() = contact.defaultDisplayName
    val customName: String? get() = contact.customName
    val peerKey: AccountPeerKey get() = AccountPeerKey(accountId, login)
}

data class AccountPeerKey(val accountId: AccountId, val login: String)

data class AccountHistoryKey(val accountId: AccountId, val id: Long)

data class AccountContactPage(
    val accountId: AccountId,
    val items: List<AccountContact>,
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

data class AccountHistory(
    val accountId: AccountId,
    val item: CallHistoryItem,
) {
    val id: Long get() = item.id
    val peerLogin: String get() = item.peerLogin
    val peerName: String get() = item.peerName
    val direction: String get() = item.direction
    val outcome: String get() = item.outcome
    val reached: Boolean get() = item.reached
    val startedAt: Long get() = item.startedAt
    val durationSeconds: Long get() = item.durationSeconds
    val key: AccountHistoryKey get() = AccountHistoryKey(accountId, id)
}

data class AccountCallHistoryPage(
    val accountId: AccountId,
    val items: List<AccountHistory>,
    val nextBefore: Long,
    val latestId: Long,
    val unread: CallUnreadState,
    val session: Session? = null,
)

data class AccountUnreadState(
    val accountId: AccountId,
    val unread: CallUnreadState,
    /** Null only for the single-account Task 6 compatibility bridge. */
    val session: Session? = null,
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

private data class SessionClaimWire(@SerializedName("session_id") val sessionId: String)

class ApiException(
    val code: Int,
    message: String,
    val authReason: String? = null,
) : RuntimeException(message)

interface HouseholdApi {
    fun serverInfo(): ServerInfo
    fun webPushConfig(): WebPushClientConfig = error("WebPush is unavailable")
    fun me(): Profile
    fun contactsPage(limit: Int = 20, cursor: String = ""): ContactPage
    fun updateContactName(login: String, customName: String?): Contact
    fun calls(limit: Int = 50, before: Long = 0, peerLogin: String? = null): CallHistoryPage
    fun markCallsRead(throughId: Long, peerLogin: String? = null): CallUnreadState
    fun putDevice(deviceId: String, subscription: WebPushSubscription, configId: String): Unit =
        error("WebPush is unavailable")
    fun claimSession(deviceId: String, subscription: WebPushSubscription, configId: String): String =
        error("WebPush is unavailable")
}

class UrlConnectionApiClient(
    private val baseUrl: String,
    private val login: String,
    private val token: String,
    private val sessionId: String? = null,
) : HouseholdApi {
    override fun serverInfo(): ServerInfo =
        get("/healthz", ServerInfoWire::class.java, authenticated = false).toServerInfo()

    override fun webPushConfig(): WebPushClientConfig =
        get("/api/webpush-config", WebPushClientConfig::class.java, includeSessionId = false)

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

    override fun putDevice(deviceId: String, subscription: WebPushSubscription, configId: String) {
        write<Unit>(
            "PUT",
            "/api/device",
            linkedMapOf(
                "device_id" to deviceId,
                "webpush_subscription" to subscription,
                "config_id" to configId,
            ),
            null,
            expectedStatus = 204,
        )
    }

    override fun claimSession(deviceId: String, subscription: WebPushSubscription, configId: String): String =
        write(
            "POST",
            "/api/session",
            linkedMapOf(
                "device_id" to deviceId,
                "webpush_subscription" to subscription,
                "config_id" to configId,
            ),
            SessionClaimWire::class.java,
            includeSessionId = false,
        ).sessionId.takeIf(String::isNotBlank) ?: throw IllegalStateException("empty session_id")

    private fun <T> put(path: String, value: Any, type: Class<T>?): T {
        return write("PUT", path, value, type)
    }

    private fun <T> write(
        method: String,
        path: String,
        value: Any,
        type: Class<T>?,
        includeSessionId: Boolean = true,
        expectedStatus: Int? = null,
    ): T {
        val body = gson.toJson(value).toByteArray(Charsets.UTF_8)
        val connection = (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 5000
            readTimeout = 5000
            doOutput = true
            authenticate(this, includeSessionId)
            setRequestProperty("Content-Type", "application/json")
            outputStream.use { it.write(body) }
        }
        val code = connection.responseCode
        if (expectedStatus?.let { code != it } ?: (code !in 200..299)) {
            throw connection.apiException(code)
        }
        if (type == null) {
            @Suppress("UNCHECKED_CAST")
            return Unit as T
        }
        return gson.fromJson(connection.inputStream.bufferedReader().readText(), type)
    }

    private fun <T> get(
        path: String,
        type: Class<T>,
        authenticated: Boolean = true,
        includeSessionId: Boolean = true,
    ): T {
        val connection = (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5000
            readTimeout = 5000
            if (authenticated) authenticate(this, includeSessionId)
        }
        val code = connection.responseCode
        if (code !in 200..299) {
            throw connection.apiException(code)
        }
        return gson.fromJson(connection.inputStream.bufferedReader().readText(), type)
    }

    private fun authenticate(connection: HttpURLConnection, includeSessionId: Boolean = true) {
        connection.setRequestProperty("Authorization", basicAuth())
        if (includeSessionId) sessionId?.let { connection.setRequestProperty(SessionIdHeader, it) }
    }

    private fun HttpURLConnection.apiException(code: Int): ApiException = ApiException(
        code,
        errorStream?.bufferedReader()?.readText() ?: "request failed",
        getHeaderField(AuthReasonHeader),
    )

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
