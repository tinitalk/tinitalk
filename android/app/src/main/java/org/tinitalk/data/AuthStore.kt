package org.tinitalk.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.tinitalk.push.StoredFirebaseConfig
import java.security.KeyStore
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

class AuthStore(
    private val store: KeyValueStore,
    private val cipher: TokenCipher,
) {
    fun save(session: Session) {
        synchronized(SessionLock) {
            saveUnlocked(session)
            AuthSessionEvents.clear()
        }
    }

    fun load(): Session? = synchronized(SessionLock) { loadUnlocked() }

    fun loadBoundTo(config: StoredFirebaseConfig?): Session? = synchronized(SessionLock) {
        loadUnlocked()?.takeIf { session -> session.isBoundTo(config) }
    }

    fun clear() = synchronized(SessionLock) {
        clearUnlocked()
        AuthSessionEvents.clear()
    }

    fun clearIfCurrent(session: Session): Boolean = synchronized(SessionLock) {
        val current = loadUnlocked()
        if (current.sameIdentity(session)) {
            clearUnlocked()
            true
        } else {
            false
        }
    }

    fun saveIfCurrent(expected: Session?, session: Session): Boolean = synchronized(SessionLock) {
        if (!loadUnlocked().sameIdentity(expected)) return@synchronized false
        saveUnlocked(session)
        AuthSessionEvents.clear()
        true
    }

    fun invalidateIfCurrent(session: Session): Boolean = synchronized(SessionLock) {
        val current = loadUnlocked()
        if (!current.sameIdentity(session)) return@synchronized false
        clearUnlocked()
        AuthSessionEvents.publish(AuthSessionEvent(requireNotNull(current)))
        true
    }

    fun updateFeatures(url: String, features: Set<String>) = synchronized(SessionLock) {
        val session = loadUnlocked() ?: return@synchronized
        if (session.url == url) saveUnlocked(session.copy(features = features))
    }

    private fun loadUnlocked(): Session? {
        val url = store.get("url") ?: return null
        val login = store.get("login") ?: return null
        val token = store.get("token") ?: return null
        val iv = store.get("iv") ?: return null
        val features = store.get("features")
            ?.let { encoded ->
                runCatching { gson.fromJson<List<String>>(encoded, featureListType).toSet() }.getOrNull()
            }
            .orEmpty()
        val sessionId = store.get("session_id")?.takeIf(String::isNotEmpty)
        val configId = store.get("config_id")?.takeIf(String::isNotEmpty)
        return Session(url, login, cipher.decrypt(CipherText(token, iv)), features, sessionId, configId)
    }

    private fun saveUnlocked(session: Session) {
        val encrypted = cipher.encrypt(session.token)
        store.put("url", session.url)
        store.put("login", session.login)
        store.put("token", encrypted.value)
        store.put("iv", encrypted.iv)
        store.put("features", gson.toJson(session.features.sorted()))
        if (session.sessionId == null) {
            store.remove("session_id")
        } else {
            store.put("session_id", session.sessionId)
        }
        if (session.configId == null) {
            store.remove("config_id")
        } else {
            store.put("config_id", session.configId)
        }
    }

    private fun clearUnlocked() {
        store.remove("url", "login", "token", "iv", "features", "session_id", "config_id")
    }

    private companion object {
        val SessionLock = Any()
        val gson = Gson()
        val featureListType = object : TypeToken<List<String>>() {}.type
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

private fun Session.isBoundTo(config: StoredFirebaseConfig?): Boolean =
    config != null &&
        !configId.isNullOrBlank() &&
        normalizeServerUrl(url) == normalizeServerUrl(config.serverUrl) &&
        configId == config.configId

internal fun normalizeServerUrl(url: String): String = url.trim().trimEnd('/')

class SharedPreferencesKeyValueStore(context: Context) : KeyValueStore {
    private val prefs: SharedPreferences = context.getSharedPreferences("auth", Context.MODE_PRIVATE)

    override fun get(key: String): String? = prefs.getString(key, null)

    override fun put(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun remove(vararg keys: String) {
        prefs.edit().apply {
            keys.forEach { remove(it) }
        }.apply()
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
