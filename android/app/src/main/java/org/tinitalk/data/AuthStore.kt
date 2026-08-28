package org.tinitalk.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
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
        synchronized(SessionLock) { saveUnlocked(session) }
    }

    fun load(): Session? = synchronized(SessionLock) { loadUnlocked() }

    fun clear() = synchronized(SessionLock) { clearUnlocked() }

    fun clearIfCurrent(session: Session) = synchronized(SessionLock) {
        val current = loadUnlocked()
        if (current != null &&
            current.url == session.url &&
            current.login == session.login &&
            current.token == session.token
        ) {
            clearUnlocked()
        }
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
        return Session(url, login, cipher.decrypt(CipherText(token, iv)), features)
    }

    private fun saveUnlocked(session: Session) {
        val encrypted = cipher.encrypt(session.token)
        store.put("url", session.url)
        store.put("login", session.login)
        store.put("token", encrypted.value)
        store.put("iv", encrypted.iv)
        store.put("features", gson.toJson(session.features.sorted()))
    }

    private fun clearUnlocked() {
        store.remove("url", "login", "token", "iv", "features")
    }

    private companion object {
        val SessionLock = Any()
        val gson = Gson()
        val featureListType = object : TypeToken<List<String>>() {}.type
    }
}

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
