package org.tinitalk.data

import android.content.Context
import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.google.gson.Gson
import org.tinitalk.push.PendingPushRegistration
import org.tinitalk.push.PushRegistrationState
import org.tinitalk.push.StoredWebPushConfig
import org.tinitalk.push.WebPushSubscription
import org.tinitalk.push.isBoundTo
import org.tinitalk.push.isValid
import java.net.URL
import java.security.KeyStore
import java.util.Locale
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class Session(
    val url: String,
    val login: String,
    val token: String,
    val features: Set<String> = emptySet(),
    val sessionId: String? = null,
    val configId: String? = null,
)

interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(vararg keys: String)
    fun values(): List<String>
}

interface TokenCipher {
    fun encrypt(plain: String): CipherText
    fun decrypt(cipherText: CipherText): String
}

data class CipherText(val value: String, val iv: String)

class AccountStorageException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

@JvmInline
value class AccountId(val value: String) {
    init {
        require(value.isNotBlank()) { "account ID must not be blank" }
    }
}

data class AccountRecord(
    val id: AccountId,
    val session: Session,
    val displayName: String? = null,
)

internal data class PersistedAccountCollection(
    val version: Int = AccountCollectionVersion,
    val accounts: List<PersistedAccount> = emptyList(),
)

internal data class PersistedAccount(
    val id: String,
    val url: String,
    val login: String,
    val token: CipherText,
    val features: Set<String> = emptySet(),
    val sessionId: String? = null,
    val configId: String? = null,
    val displayName: String? = null,
    val webPushConfig: StoredWebPushConfig? = null,
    val webPushRegistration: PushRegistrationState? = null,
)

internal const val AccountCollectionKey = "accounts_v1"
internal const val AccountCollectionVersion = 1
internal val AccountStorageLock = Any()

internal object AccountCollectionStorage {
    private val gson = Gson()

    fun read(store: KeyValueStore): PersistedAccountCollection {
        val encoded = store.get(AccountCollectionKey) ?: return PersistedAccountCollection()
        return runCatching {
            val collection = requireNotNull(gson.fromJson(encoded, PersistedAccountCollection::class.java))
            require(collection.version == AccountCollectionVersion)
            val identities = mutableSetOf<Pair<String, String>>()
            val ids = mutableSetOf<String>()
            require(collection.accounts.all { account ->
                account.id.isNotBlank() &&
                    ids.add(account.id) &&
                    account.url.isNotBlank() &&
                    account.login.isNotBlank() &&
                    account.token.value.isNotBlank() &&
                    account.token.iv.isNotBlank() &&
                    account.features.size >= 0 &&
                    account.webPushConfig?.isValid() != false &&
                    account.webPushRegistration?.isValid() != false &&
                    identities.add(normalizeServerUrl(account.url) to account.login.trim())
            })
            val privateCollection = collection.copy(accounts = collection.accounts.map { it.copy(displayName = null) })
            if (privateCollection != collection) write(store, privateCollection)
            privateCollection
        }.getOrElse { error ->
            throw AccountStorageException("invalid account collection", error)
        }
    }

    fun write(store: KeyValueStore, collection: PersistedAccountCollection) {
        val previous = store.get(AccountCollectionKey)
        try {
            store.put(AccountCollectionKey, gson.toJson(
                collection.copy(accounts = collection.accounts.map { it.copy(displayName = null) }),
            ))
        } catch (error: Exception) {
            val rollback = runCatching {
                if (previous == null) store.remove(AccountCollectionKey) else store.put(AccountCollectionKey, previous)
            }
            throw AccountStorageException("failed to persist account collection", error).also { failure ->
                rollback.exceptionOrNull()?.let(failure::addSuppressed)
            }
        }
    }
}

class AuthStore(
    private val store: KeyValueStore,
    private val cipher: TokenCipher,
    private val accountIdFactory: () -> AccountId = { AccountId(UUID.randomUUID().toString()) },
) {
    fun save(session: Session) {
        synchronized(AccountStorageLock) {
            val collection = readCollectionUnlocked()
            val first = collection.accounts.firstOrNull()
            if (first == null) {
                upsertUnlocked(collection, session)
            } else {
                require(collection.canReplace(first.id, session)) { "duplicate account identity" }
                replaceUnlocked(collection, first.id, session)
            }
            AuthSessionEvents.clear()
        }
    }

    fun load(): Session? = synchronized(AccountStorageLock) {
        readCollectionUnlocked().accounts.firstOrNull()?.toSession()
    }

    fun loadBoundTo(config: StoredWebPushConfig?): Session? = synchronized(AccountStorageLock) {
        readCollectionUnlocked().accounts.firstOrNull()?.toSession()?.takeIf { session -> session.isBoundTo(config) }
    }

    fun clear() = synchronized(AccountStorageLock) {
        val collection = readCollectionUnlocked()
        collection.accounts.firstOrNull()?.let { removeUnlocked(collection, it.id) }
        AuthSessionEvents.clear()
    }

    fun clearIfCurrent(session: Session): Boolean = synchronized(AccountStorageLock) {
        val first = readCollectionUnlocked().accounts.firstOrNull() ?: return@synchronized false
        removeIfCurrentUnlocked(first.id, session)
    }

    fun saveIfCurrent(expected: Session?, session: Session): Boolean = synchronized(AccountStorageLock) {
        val collection = readCollectionUnlocked()
        val first = collection.accounts.firstOrNull()
        if (!first?.toSession().sameIdentity(expected)) return@synchronized false
        if (first != null && !collection.canReplace(first.id, session)) return@synchronized false
        if (first == null) upsertUnlocked(collection, session) else replaceUnlocked(collection, first.id, session)
        AuthSessionEvents.clear()
        true
    }

    fun invalidateIfCurrent(session: Session): Boolean = synchronized(AccountStorageLock) {
        val first = readCollectionUnlocked().accounts.firstOrNull() ?: return@synchronized false
        val current = first.toSession()
        if (!removeIfCurrentUnlocked(first.id, session)) return@synchronized false
        AuthSessionEvents.publish(AuthSessionEvent(current))
        true
    }

    fun updateFeatures(url: String, features: Set<String>) = synchronized(AccountStorageLock) {
        val collection = readCollectionUnlocked()
        val normalizedUrl = normalizeServerUrl(url)
        val accounts = collection.accounts.map { account ->
            if (normalizeServerUrl(account.url) == normalizedUrl && account.features != features) {
                account.copy(features = features)
            } else {
                account
            }
        }
        if (accounts != collection.accounts) {
            AccountCollectionStorage.write(store, collection.copy(accounts = accounts))
        }
    }

    fun list(): List<AccountRecord> = synchronized(AccountStorageLock) {
        readCollectionUnlocked().accounts.map { AccountRecord(AccountId(it.id), it.toSession(), it.displayName) }
    }

    fun get(accountId: AccountId): AccountRecord? = synchronized(AccountStorageLock) {
        readCollectionUnlocked().accounts.firstOrNull { it.id == accountId.value }
            ?.let { AccountRecord(accountId, it.toSession(), it.displayName) }
    }

    fun webPushConfig(accountId: AccountId): StoredWebPushConfig? = synchronized(AccountStorageLock) {
        readCollectionUnlocked().accounts.firstOrNull { it.id == accountId.value }?.webPushConfig
    }

    fun isCurrent(accountId: AccountId, session: Session): Boolean = synchronized(AccountStorageLock) {
        readCollectionUnlocked().accounts.firstOrNull { it.id == accountId.value }
            ?.toSession()
            .sameIdentity(session)
    }

    fun <T> withCurrent(accountId: AccountId, session: Session, block: () -> T): T? = synchronized(AccountStorageLock) {
        val current = readCollectionUnlocked().accounts.firstOrNull { it.id == accountId.value }?.toSession()
        if (!current.sameIdentity(session)) null else block()
    }

    fun upsert(session: Session): AccountRecord = synchronized(AccountStorageLock) {
        upsertUnlocked(readCollectionUnlocked(), session)
    }

    fun newAccountId(): AccountId = synchronized(AccountStorageLock) {
        generateAccountId(readCollectionUnlocked())
    }

    fun add(
        accountId: AccountId,
        session: Session,
        webPushConfig: StoredWebPushConfig,
    ): AccountRecord = synchronized(AccountStorageLock) {
        val collection = readCollectionUnlocked()
        require(session.sessionId?.isNotBlank() == true) { "session ID is required" }
        require(session.configId?.isNotBlank() == true) { "config ID is required" }
        require(webPushConfig.isValid() && session.isBoundTo(webPushConfig)) { "WebPush configuration does not match session" }
        require(collection.accounts.none { it.id == accountId.value }) { "duplicate account ID" }
        require(collection.accounts.none { sameServerUrl(it.url, session.url) }) { "duplicate server" }
        val persisted = persistedAccount(accountId.value, session).copy(
            webPushConfig = webPushConfig,
        )
        AccountCollectionStorage.write(store, collection.copy(accounts = collection.accounts + persisted))
        AccountRecord(accountId, session, persisted.displayName)
    }

    fun remove(accountId: AccountId): Boolean = synchronized(AccountStorageLock) {
        removeUnlocked(readCollectionUnlocked(), accountId.value)
    }

    fun saveIfCurrent(accountId: AccountId, expected: Session, session: Session): Boolean =
        synchronized(AccountStorageLock) {
            val collection = readCollectionUnlocked()
            val current = collection.accounts.firstOrNull { it.id == accountId.value } ?: return@synchronized false
            if (!current.toSession().sameIdentity(expected)) return@synchronized false
            if (!collection.canReplace(current.id, session)) return@synchronized false
            replaceUnlocked(collection, current.id, session)
            true
        }

    fun activateWebPushIfCurrent(
        accountId: AccountId,
        expected: Session,
        session: Session,
        config: StoredWebPushConfig,
    ): Boolean = synchronized(AccountStorageLock) {
        val collection = readCollectionUnlocked()
        val current = collection.accounts.firstOrNull { it.id == accountId.value } ?: return@synchronized false
        if (!current.toSession().sameIdentity(expected) || !collection.canReplace(current.id, session)) {
            return@synchronized false
        }
        require(config.isValid() && session.isBoundTo(config)) { "WebPush configuration does not match session" }
        val replacement = persistedAccount(current.id, session, current).copy(
            webPushConfig = config,
            webPushRegistration = null,
        )
        AccountCollectionStorage.write(
            store,
            collection.copy(accounts = collection.accounts.map { if (it.id == current.id) replacement else it }),
        )
        true
    }

    fun activateWebPushRegistrationIfCurrent(
        accountId: AccountId,
        expected: Session,
        session: Session,
        config: StoredWebPushConfig,
        deviceId: String,
        subscription: WebPushSubscription,
    ): Boolean = synchronized(AccountStorageLock) {
        val collection = readCollectionUnlocked()
        val current = collection.accounts.firstOrNull { it.id == accountId.value } ?: return@synchronized false
        if (!current.toSession().sameIdentity(expected) || !collection.canReplace(current.id, session)) {
            return@synchronized false
        }
        require(config.isValid() && config.isBoundTo(session)) { "WebPush configuration does not match session" }
        require(deviceId.isNotBlank() && subscription.isValid()) { "invalid push registration" }
        val generation = (current.webPushRegistration?.generation ?: 0) + 1
        val pending = PendingPushRegistration(
            serverUrl = normalizeServerUrl(session.url),
            configId = config.configId,
            deviceId = deviceId,
            sessionId = requireNotNull(session.sessionId),
            subscription = subscription,
            generation = generation,
        )
        val replacement = persistedAccount(current.id, session, current).copy(
            webPushConfig = config,
            webPushRegistration = PushRegistrationState(generation, pending),
        )
        AccountCollectionStorage.write(
            store,
            collection.copy(accounts = collection.accounts.map { if (it.id == current.id) replacement else it }),
        )
        true
    }

    fun removeIfCurrent(accountId: AccountId, session: Session): Boolean = synchronized(AccountStorageLock) {
        readCollectionUnlocked()
        removeIfCurrentUnlocked(accountId.value, session)
    }

    fun invalidateIfCurrent(
        accountId: AccountId,
        session: Session,
        reason: AuthRemovalReason = AuthRemovalReason.SessionReplaced,
    ): Boolean = synchronized(AccountStorageLock) {
        val current = readCollectionUnlocked().accounts.firstOrNull { it.id == accountId.value }
            ?.toSession()
            ?: return@synchronized false
        if (!removeIfCurrentUnlocked(accountId.value, session)) return@synchronized false
        AuthSessionEvents.publish(AuthSessionEvent(accountId, current, reason))
        true
    }

    private fun readCollectionUnlocked(): PersistedAccountCollection = AccountCollectionStorage.read(store)

    private fun persistedAccount(id: String, session: Session, previous: PersistedAccount? = null): PersistedAccount {
        val encrypted = cipher.encrypt(session.token)
        return PersistedAccount(
            id = id,
            url = session.url,
            login = session.login,
            token = encrypted,
            features = session.features,
            sessionId = session.sessionId,
            configId = session.configId,
            webPushConfig = previous?.webPushConfig,
            webPushRegistration = previous?.webPushRegistration,
        )
    }

    private fun upsertUnlocked(collection: PersistedAccountCollection, session: Session): AccountRecord {
        val duplicate = collection.accounts.firstOrNull { it.hasSemanticIdentity(session) }
        val id = duplicate?.id ?: accountIdFactory().value
        require(duplicate != null || collection.accounts.none { it.id == id }) { "duplicate account ID" }
        val replacement = persistedAccount(id, session, duplicate)
        val accounts = if (duplicate == null) {
            collection.accounts + replacement
        } else {
            collection.accounts.map { if (it.id == id) replacement else it }
        }
        AccountCollectionStorage.write(store, collection.copy(accounts = accounts))
        return AccountRecord(AccountId(id), session, replacement.displayName)
    }

    private fun replaceUnlocked(collection: PersistedAccountCollection, id: String, session: Session) {
        require(collection.canReplace(id, session)) { "duplicate account identity" }
        val current = collection.accounts.first { it.id == id }
        val replacement = persistedAccount(id, session, current)
        AccountCollectionStorage.write(
            store,
            collection.copy(accounts = collection.accounts.map { if (it.id == id) replacement else it }),
        )
    }

    private fun removeUnlocked(collection: PersistedAccountCollection, id: String): Boolean {
        if (collection.accounts.none { it.id == id }) return false
        AccountCollectionStorage.write(store, collection.copy(accounts = collection.accounts.filterNot { it.id == id }))
        return true
    }

    private fun removeIfCurrentUnlocked(id: String, session: Session): Boolean {
        val collection = AccountCollectionStorage.read(store)
        val current = collection.accounts.firstOrNull { it.id == id } ?: return false
        if (!current.toSession().sameIdentity(session)) return false
        return removeUnlocked(collection, id)
    }

    private fun PersistedAccount.toSession(): Session = Session(
        url = url,
        login = login,
        token = cipher.decrypt(token),
        features = features,
        sessionId = sessionId,
        configId = configId,
    )

    private fun PersistedAccount.hasSemanticIdentity(session: Session): Boolean =
        sameServerUrl(url, session.url) &&
            login.trim() == session.login.trim()

    private fun PersistedAccountCollection.canReplace(id: String, session: Session): Boolean =
        accounts.none { account -> account.id != id && account.hasSemanticIdentity(session) }

    private fun generateAccountId(collection: PersistedAccountCollection): AccountId {
        while (true) {
            val candidate = accountIdFactory()
            if (collection.accounts.none { it.id == candidate.value }) return candidate
        }
    }

}

internal fun Session?.sameIdentity(other: Session?): Boolean = when {
    this == null || other == null -> this == null && other == null
    else -> url == other.url &&
        login == other.login &&
        token == other.token &&
        sessionId == other.sessionId &&
        configId == other.configId
}

private fun Session.isBoundTo(config: StoredWebPushConfig?): Boolean =
    config != null &&
        !configId.isNullOrBlank() &&
        normalizeServerUrl(url) == normalizeServerUrl(config.serverUrl) &&
        configId == config.configId

internal fun normalizeServerUrl(url: String): String {
    val value = url.trim().trimEnd('/')
    val parsed = runCatching { URL(value) }.getOrNull() ?: return value
    if (!parsed.protocol.equals("https", ignoreCase = true)) return value
    val host = parsed.host
        ?.removePrefix("[")
        ?.removeSuffix("]")
        ?.lowercase(Locale.ROOT)
        ?.takeIf(String::isNotBlank)
        ?: return value
    return buildString {
        append("https://")
        parsed.userInfo?.let { append(it).append('@') }
        if (':' in host) append('[').append(host).append(']') else append(host)
        if (parsed.port != -1 && parsed.port != 443) append(':').append(parsed.port)
        append(parsed.path.orEmpty().trimEnd('/'))
        parsed.query?.let { append('?').append(it) }
        parsed.ref?.let { append('#').append(it) }
    }
}

internal fun sameServerUrl(first: String, second: String): Boolean =
    normalizeServerUrl(first) == normalizeServerUrl(second)

internal fun httpsServerUrl(url: String): String? {
    val value = url.trim()
    val address = when {
        value.startsWith("https://", ignoreCase = true) -> value.substring("https://".length)
        "://" in value -> return null
        else -> value
    }.trimEnd('/')
    return address.takeIf(String::isNotBlank)?.let { normalizeServerUrl("https://$it") }
}

internal class SharedPreferencesKeyValueStore(context: Context) : KeyValueStore {
    private val prefs: SharedPreferences = context.getSharedPreferences("auth", Context.MODE_PRIVATE)

    override fun get(key: String): String? = prefs.getString(key, null)

    // KTX edit(commit = true) discards the result; authentication must detect disk-write failure.
    @SuppressLint("UseKtx")
    override fun put(key: String, value: String) {
        check(prefs.edit().putString(key, value).commit()) { "failed to persist auth state" }
    }

    // Keep the synchronous commit result for the same reason as put().
    @SuppressLint("UseKtx")
    override fun remove(vararg keys: String) {
        check(prefs.edit().apply {
            keys.forEach { remove(it) }
        }.commit()) { "failed to remove auth state" }
    }

    override fun values(): List<String> = prefs.all.values.mapNotNull { it as? String }

}

class AndroidKeystoreTokenCipher : TokenCipher {
    override fun encrypt(plain: String): CipherText {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return CipherText(
            android.util.Base64.encodeToString(cipher.doFinal(plain.toByteArray(Charsets.UTF_8)), android.util.Base64.NO_WRAP),
            android.util.Base64.encodeToString(cipher.iv, android.util.Base64.NO_WRAP),
        )
    }

    override fun decrypt(cipherText: CipherText): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val iv = android.util.Base64.decode(cipherText.iv, android.util.Base64.NO_WRAP)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        val raw = android.util.Base64.decode(cipherText.value, android.util.Base64.NO_WRAP)
        return String(cipher.doFinal(raw), Charsets.UTF_8)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEY_ALIAS = "tinitalk_auth"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

class MemoryKeyValueStore : KeyValueStore {
    private val map = linkedMapOf<String, String>()

    override fun get(key: String): String? = map[key]
    override fun put(key: String, value: String) {
        map[key] = value
    }
    override fun remove(vararg keys: String) {
        keys.forEach { map.remove(it) }
    }
    override fun values(): List<String> = map.values.toList()
}

class PrefixTokenCipher : TokenCipher {
    override fun encrypt(plain: String): CipherText = CipherText(plain.reversed(), "iv")
    override fun decrypt(cipherText: CipherText): String = cipherText.value.reversed()
}
