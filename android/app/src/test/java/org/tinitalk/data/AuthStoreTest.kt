package org.tinitalk.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class AuthStoreTest {
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
}
