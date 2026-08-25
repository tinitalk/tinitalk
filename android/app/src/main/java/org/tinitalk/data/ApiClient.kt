package org.tinitalk.data

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.net.HttpURLConnection
import java.net.URL
import android.util.Base64

data class Profile(val login: String, @SerializedName("display_name") val displayName: String)
data class Contact(val login: String, @SerializedName("display_name") val displayName: String)

class ApiException(val code: Int, message: String) : RuntimeException(message)

interface HouseholdApi {
    fun me(): Profile
    fun contacts(): List<Contact>
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
        return "Basic " + Base64.encodeToString(raw, Base64.NO_WRAP)
    }

    private companion object {
        val gson = Gson()
    }
}
