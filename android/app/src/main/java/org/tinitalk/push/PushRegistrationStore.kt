package org.tinitalk.push

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import org.tinitalk.data.Session
import org.tinitalk.data.normalizeServerUrl

internal data class PendingPushRegistration(
    @SerializedName("server_url") val serverUrl: String,
    @SerializedName("config_id") val configId: String,
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("session_id") val sessionId: String,
    @SerializedName("firebase_installation_id") val installationId: String,
    val generation: Long,
)

internal enum class PushRegistrationClearResult {
    CLEARED,
    STALE,
    FAILED,
}

internal class PushRegistrationCommitException : IllegalStateException("failed to persist push registration")

internal interface PushRegistrationPersistence {
    fun read(): String?
    fun commit(value: String?): Boolean
}

internal class PushRegistrationStore internal constructor(
    private val persistence: PushRegistrationPersistence,
) {
    constructor(context: Context) : this(SharedPreferencesPushRegistrationPersistence(context))

    fun upsert(
        serverUrl: String,
        configId: String,
        deviceId: String,
        sessionId: String,
        installationId: String,
    ): PendingPushRegistration = synchronized(StoreLock) {
        val previousEncoded = persistence.read()
        val current = readState(previousEncoded)
        val pending = PendingPushRegistration(
            serverUrl = normalizeServerUrl(serverUrl),
            configId = configId,
            deviceId = deviceId,
            sessionId = sessionId,
            installationId = installationId,
            generation = current.generation + 1,
        )
        require(pending.isValid()) { "invalid push registration" }
        if (!persistence.commit(gson.toJson(PushRegistrationState(pending.generation, pending)))) {
            persistence.commit(previousEncoded)
            throw PushRegistrationCommitException()
        }
        pending
    }

    fun load(): PendingPushRegistration? = synchronized(StoreLock) {
        readState().pending?.takeIf { it.isValid() }
    }

    fun loadBoundTo(
        config: StoredFirebaseConfig?,
        session: Session?,
        deviceId: String,
    ): PendingPushRegistration? = synchronized(StoreLock) {
        val pending = readState().pending?.takeIf { it.isValid() } ?: return@synchronized null
        pending.takeIf {
            config != null &&
                session != null &&
                normalizeServerUrl(it.serverUrl) == normalizeServerUrl(config.serverUrl) &&
                it.configId == config.configId &&
                normalizeServerUrl(session.url) == normalizeServerUrl(config.serverUrl) &&
                session.configId == config.configId &&
                session.sessionId == it.sessionId &&
                it.deviceId == deviceId
        }
    }

    fun clearIfGeneration(generation: Long): PushRegistrationClearResult = synchronized(StoreLock) {
        val previousEncoded = persistence.read()
        val current = readState(previousEncoded)
        if (current.pending?.generation != generation) return@synchronized PushRegistrationClearResult.STALE
        if (persistence.commit(gson.toJson(current.copy(pending = null)))) {
            PushRegistrationClearResult.CLEARED
        } else {
            persistence.commit(previousEncoded)
            PushRegistrationClearResult.FAILED
        }
    }

    private fun readState(encoded: String? = persistence.read()): PushRegistrationState {
        encoded ?: return PushRegistrationState()
        return runCatching { gson.fromJson(encoded, PushRegistrationState::class.java) }
            .getOrNull()
            ?.takeIf { it.generation >= 0 }
            ?: PushRegistrationState()
    }

    private fun PendingPushRegistration.isValid(): Boolean =
        serverUrl.isNotBlank() &&
            configId.isNotBlank() &&
            deviceId.isNotBlank() &&
            sessionId.isNotBlank() &&
            installationId.isNotBlank() &&
            generation > 0

    private companion object {
        val StoreLock = Any()
        val gson = Gson()
    }
}

private data class PushRegistrationState(
    val generation: Long = 0,
    val pending: PendingPushRegistration? = null,
)

private class SharedPreferencesPushRegistrationPersistence(context: Context) : PushRegistrationPersistence {
    private val preferences: SharedPreferences =
        context.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)

    override fun read(): String? = preferences.getString(StateKey, null)

    override fun commit(value: String?): Boolean = preferences.edit().apply {
        if (value == null) remove(StateKey) else putString(StateKey, value)
    }.commit()

    private companion object {
        const val PreferencesName = "push_registration"
        const val StateKey = "state"
    }
}
