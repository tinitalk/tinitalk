package org.tinitalk.push

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

data class StoredFirebaseConfig(
    @SerializedName("server_url") val serverUrl: String,
    @SerializedName("application_id") val applicationId: String,
    @SerializedName("api_key") val apiKey: String,
    @SerializedName("project_id") val projectId: String,
    @SerializedName("gcm_sender_id") val gcmSenderId: String,
    @SerializedName("config_id") val configId: String,
)

class FirebaseConfigCommitException : IllegalStateException("failed to persist Firebase configuration")

internal interface FirebaseConfigPersistence {
    fun read(): String?
    fun commit(value: String): Boolean
}

class FirebaseConfigStore internal constructor(
    private val persistence: FirebaseConfigPersistence,
) {
    constructor(context: Context) : this(SharedPreferencesFirebaseConfigPersistence(context))

    fun save(serverUrl: String, config: FirebaseClientConfig): StoredFirebaseConfig {
        val stored = StoredFirebaseConfig(
            serverUrl = serverUrl.trim().trimEnd('/'),
            applicationId = config.applicationId,
            apiKey = config.apiKey,
            projectId = config.projectId,
            gcmSenderId = config.gcmSenderId,
            configId = config.configId,
        )
        require(stored.isValid()) { "invalid Firebase configuration" }
        if (!persistence.commit(gson.toJson(stored))) throw FirebaseConfigCommitException()
        return stored
    }

    fun load(): StoredFirebaseConfig? {
        val encoded = persistence.read() ?: return null
        return runCatching {
            gson.fromJson(encoded, StoredFirebaseConfig::class.java)?.takeIf { it.isValid() }
        }.getOrNull()
    }

    private fun StoredFirebaseConfig.isValid(): Boolean =
        serverUrl.isNotBlank() &&
            applicationId.isNotBlank() &&
            apiKey.isNotBlank() &&
            projectId.isNotBlank() &&
            gcmSenderId.isNotBlank() &&
            configId.isNotBlank()

    private companion object {
        val gson = Gson()
    }
}

private class SharedPreferencesFirebaseConfigPersistence(context: Context) : FirebaseConfigPersistence {
    private val preferences: SharedPreferences =
        context.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)

    override fun read(): String? = preferences.getString(ConfigKey, null)

    override fun commit(value: String): Boolean = preferences.edit().putString(ConfigKey, value).commit()

    private companion object {
        const val PreferencesName = "firebase_config"
        const val ConfigKey = "config"
    }
}
