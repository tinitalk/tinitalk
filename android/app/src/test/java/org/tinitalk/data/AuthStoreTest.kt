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
        assertNull(session?.sessionId)
    }

    @Test
    fun savesAndLoadsServerFeaturesWithSession() {
        val store = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher())
        val session = Session("https://host", "alice", "token", setOf("video_1to1", "future_feature"))

        store.save(session)

        assertEquals(session, store.load())
    }

    @Test
    fun savesAndLoadsOpaqueSessionId() {
        val store = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher())
        val session = Session(
            "https://host",
            "alice",
            "token",
            setOf("single_device_session"),
            sessionId = "session-123",
        )

        store.save(session)

        assertEquals(session, store.load())
    }

    @Test
    fun savingLegacySessionRemovesPreviousSessionId() {
        val store = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher())
        store.save(Session("https://host", "alice", "token", sessionId = "session-123"))

        store.save(Session("https://host", "alice", "token"))

        assertNull(store.load()?.sessionId)
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

    @Test
    fun conditionalClearTreatsSessionIdAsPartOfIdentity() {
        val store = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher())
        val revoked = Session("https://host", "alice", "token", sessionId = "session-old")
        val replacement = revoked.copy(sessionId = "session-new")
        store.save(replacement)

        val cleared = store.clearIfCurrent(revoked)

        assertFalse(cleared)
        assertEquals(replacement, store.load())
    }

    @Test
    fun conditionalSaveCannotResurrectClearedSession() {
        val store = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher())
        val restored = Session("https://host", "alice", "token", sessionId = "session-old")
        store.save(restored)
        assertTrue(store.clearIfCurrent(restored))

        val saved = store.saveIfCurrent(restored, restored.copy(features = setOf("single_device_session")))

        assertFalse(saved)
        assertNull(store.load())
    }

    @Test
    fun conditionalSaveInstallsManualSessionWhenStartingIdentityIsUnchanged() {
        val store = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher())
        val previous = Session("https://host", "alice", "old-token")
        val claimed = Session("https://host", "alice", "new-token", sessionId = "session-new")
        store.save(previous)

        val saved = store.saveIfCurrent(previous, claimed)

        assertTrue(saved)
        assertEquals(claimed, store.load())
    }

    @Test
    fun replacementInvalidationPublishesOnlyAfterExactConditionalClear() {
        AuthSessionEvents.clear()
        val events = mutableListOf<AuthSessionEvent>()
        val observer: (AuthSessionEvent) -> Unit = events::add
        AuthSessionEvents.observe(observer)
        try {
            val store = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher())
            val old = Session("https://host", "alice", "token", sessionId = "session-old")
            val current = old.copy(sessionId = "session-new")
            store.save(current)

            assertFalse(store.invalidateIfCurrent(old))
            assertTrue(events.isEmpty())
            assertTrue(store.invalidateIfCurrent(current))
            assertEquals(listOf(AuthSessionEvent(current)), events)
            assertNull(store.load())
        } finally {
            AuthSessionEvents.removeObserver(observer)
            AuthSessionEvents.clear()
        }
    }

    @Test
    fun savingNewSessionClearsStickyReplacementEvent() {
        AuthSessionEvents.clear()
        val store = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher())
        val old = Session("https://host", "alice", "token", sessionId = "session-old")
        store.save(old)
        assertTrue(store.invalidateIfCurrent(old))

        store.save(old.copy(sessionId = "session-new"))
        val replayed = mutableListOf<AuthSessionEvent>()
        val observer: (AuthSessionEvent) -> Unit = replayed::add
        AuthSessionEvents.observe(observer)
        try {
            assertTrue(replayed.isEmpty())
        } finally {
            AuthSessionEvents.removeObserver(observer)
            AuthSessionEvents.clear()
        }
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
