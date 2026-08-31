package org.tinitalk.push

import android.content.Context
import com.google.gson.annotations.SerializedName
import org.tinitalk.data.AccountCollectionStorage
import org.tinitalk.data.AccountId
import org.tinitalk.data.AccountStorageException
import org.tinitalk.data.AccountStorageLock
import org.tinitalk.data.KeyValueStore
import org.tinitalk.data.Session
import org.tinitalk.data.SharedPreferencesKeyValueStore
import org.tinitalk.data.normalizeServerUrl
import org.tinitalk.data.sameIdentity

internal data class PendingPushRegistration(
    @SerializedName("server_url") val serverUrl: String,
    @SerializedName("config_id") val configId: String,
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("session_id") val sessionId: String,
    @SerializedName("subscription") val subscription: WebPushSubscription,
    @SerializedName("generation") val generation: Long,
)

internal enum class PushRegistrationClearResult {
    CLEARED,
    STALE,
    FAILED,
}

internal data class PushRegistrationState(
    @SerializedName("generation") val generation: Long = 0,
    @SerializedName("pending") val pending: PendingPushRegistration? = null,
)

internal class PushRegistrationStore(
    private val accountPersistence: KeyValueStore,
) {
    constructor(context: Context) : this(SharedPreferencesKeyValueStore(context))

    fun upsert(
        accountId: AccountId,
        session: Session,
        deviceId: String,
        subscription: WebPushSubscription,
    ): PendingPushRegistration = synchronized(AccountStorageLock) {
        val collection = AccountCollectionStorage.read(accountPersistence)
        val current = collection.accounts.firstOrNull { it.id == accountId.value }
            ?: throw IllegalArgumentException("unknown account ID")
        val config = current.webPushConfig
            ?: throw IllegalArgumentException("account WebPush configuration is unavailable")
        require(current.toSessionIdentity(session)) { "push registration session does not match account" }
        require(config.isBoundTo(session)) { "push registration configuration does not match account" }
        require(deviceId.isNotBlank() && subscription.isValid()) { "invalid push registration" }

        val state = current.webPushRegistration ?: PushRegistrationState()
        val pending = PendingPushRegistration(
            serverUrl = normalizeServerUrl(session.url),
            configId = config.configId,
            deviceId = deviceId,
            sessionId = requireNotNull(session.sessionId),
            subscription = subscription,
            generation = state.generation + 1,
        )
        AccountCollectionStorage.write(
            accountPersistence,
            collection.copy(accounts = collection.accounts.map {
                if (it.id == accountId.value) {
                    it.copy(webPushRegistration = PushRegistrationState(pending.generation, pending))
                } else {
                    it
                }
            }),
        )
        pending
    }

    fun load(accountId: AccountId): PendingPushRegistration? = synchronized(AccountStorageLock) {
        AccountCollectionStorage.read(accountPersistence).accounts
            .firstOrNull { it.id == accountId.value }
            ?.webPushRegistration
            ?.pending
            ?.takeIf(PendingPushRegistration::isValid)
    }

    fun loadBoundTo(
        accountId: AccountId,
        config: StoredWebPushConfig?,
        session: Session?,
        deviceId: String,
    ): PendingPushRegistration? = synchronized(AccountStorageLock) {
        load(accountId)?.takeIf { it.isBoundTo(config, session, deviceId) }
    }

    fun clearIfGeneration(accountId: AccountId, generation: Long): PushRegistrationClearResult =
        synchronized(AccountStorageLock) {
            val collection = AccountCollectionStorage.read(accountPersistence)
            val current = collection.accounts.firstOrNull { it.id == accountId.value }
                ?: return@synchronized PushRegistrationClearResult.STALE
            if (current.webPushRegistration?.pending?.generation != generation) {
                return@synchronized PushRegistrationClearResult.STALE
            }
            try {
                AccountCollectionStorage.write(
                    accountPersistence,
                    collection.copy(accounts = collection.accounts.map {
                        if (it.id == accountId.value) {
                            it.copy(webPushRegistration = PushRegistrationState(generation, null))
                        } else {
                            it
                        }
                    }),
                )
            } catch (_: AccountStorageException) {
                return@synchronized PushRegistrationClearResult.FAILED
            }
            PushRegistrationClearResult.CLEARED
        }

    fun remove(accountId: AccountId): Boolean = synchronized(AccountStorageLock) {
        val collection = AccountCollectionStorage.read(accountPersistence)
        val current = collection.accounts.firstOrNull { it.id == accountId.value } ?: return@synchronized false
        if (current.webPushRegistration?.pending == null) return@synchronized false
        AccountCollectionStorage.write(
            accountPersistence,
            collection.copy(accounts = collection.accounts.map {
                if (it.id == accountId.value) it.copy(webPushRegistration = null) else it
            }),
        )
        true
    }
}

private fun org.tinitalk.data.PersistedAccount.toSessionIdentity(session: Session): Boolean =
    normalizeServerUrl(url) == normalizeServerUrl(session.url) &&
        login == session.login &&
        sessionId == session.sessionId &&
        configId == session.configId

private fun PendingPushRegistration.isBoundTo(
    config: StoredWebPushConfig?,
    session: Session?,
    deviceId: String,
): Boolean = config != null && session != null &&
    normalizeServerUrl(serverUrl) == normalizeServerUrl(config.serverUrl) &&
    configId == config.configId && config.isBoundTo(session) &&
    session.sessionId == sessionId && this.deviceId == deviceId

private fun PendingPushRegistration.isValid(): Boolean =
    serverUrl.isNotBlank() && configId.isNotBlank() && deviceId.isNotBlank() &&
        sessionId.isNotBlank() && subscription.isValid() && generation > 0

internal fun PushRegistrationState.isValid(): Boolean =
    generation >= 0 && (pending == null || pending.generation == generation && pending.isValid())
