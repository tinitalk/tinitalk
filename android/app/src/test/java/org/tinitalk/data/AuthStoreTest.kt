package org.tinitalk.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class AuthStoreTest {
    @Test
    fun loadsLegacySessionWithEmptyServerFeatures() {
        val prefs = MemoryKeyValueStore().apply {
            put("url", "https://host")
            put("login", "alice")
            put("token", "nekot-terces")
            put("iv", "iv")
        }

        val session = AuthStore(prefs, PrefixTokenCipher()).load()

        assertEquals("https://host", session?.url)
        assertEquals("alice", session?.login)
        assertEquals("secret-token", session?.token)
        assertEquals(emptySet<String>(), session?.features)
    }

    @Test
    fun savesAndLoadsServerFeaturesWithSession() {
        val store = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher())
        val session = Session("https://host", "alice", "token", setOf("video_1to1", "future_feature"))

        store.save(session)

        assertEquals(session, store.load())
    }

    @Test
    fun savesCiphertextInsteadOfPlainToken() {
        val prefs = MemoryKeyValueStore()
        val store = AuthStore(prefs, PrefixTokenCipher())

        store.save(Session("https://host", "alice", "secret-token"))

        assertFalse(prefs.values().any { it.contains("secret-token") })
        assertEquals(Session("https://host", "alice", "secret-token"), store.load())
    }

    @Test
    fun clearRemovesSession() {
        val store = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher())
        store.save(Session("https://host", "alice", "secret-token"))

        store.clear()

        assertNull(store.load())
    }

    @Test
    fun conditionalClearDoesNotEraseConcurrentSaveFromAnotherStore() {
        val values = PausingKeyValueStore()
        val first = AuthStore(values, PrefixTokenCipher())
        val second = AuthStore(values, PrefixTokenCipher())
        val oldSession = Session("https://host", "alice", "old")
        val newSession = Session("https://host", "bob", "new")
        first.save(oldSession)
        values.pauseNextIvRead.set(true)

        val clearing = thread { first.clearIfCurrent(oldSession) }
        assertTrue(values.ivReadStarted.await(1, TimeUnit.SECONDS))
        val saveFinished = CountDownLatch(1)
        val saving = thread {
            second.save(newSession)
            saveFinished.countDown()
        }
        saveFinished.await(1, TimeUnit.SECONDS)
        values.releaseIvRead.countDown()
        clearing.join()
        saving.join()

        assertEquals(newSession, first.load())
    }
}

private class PausingKeyValueStore : KeyValueStore {
    private val values = ConcurrentHashMap<String, String>()
    val pauseNextIvRead = AtomicBoolean(false)
    val ivReadStarted = CountDownLatch(1)
    val releaseIvRead = CountDownLatch(1)

    override fun get(key: String): String? {
        val value = values[key]
        if (key == "iv" && pauseNextIvRead.compareAndSet(true, false)) {
            ivReadStarted.countDown()
            releaseIvRead.await(2, TimeUnit.SECONDS)
        }
        return value
    }

    override fun put(key: String, value: String) {
        values[key] = value
    }

    override fun remove(vararg keys: String) {
        keys.forEach(values::remove)
    }

    override fun values(): List<String> = values.values.toList()
}
