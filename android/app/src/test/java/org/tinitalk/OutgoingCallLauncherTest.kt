package org.tinitalk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.tinitalk.data.*

class OutgoingCallLauncherTest {
    private val store = MemoryKeyValueStore()
    private val auth = AuthStore(store, PrefixTokenCipher())
    private val cache = ContactCache(store)

    @Test
    fun resolvesCurrentContactWithinExactAccountAndRejectsDeletedEntries() {
        val a = auth.upsert(Session("https://a.example", "me", "a"))
        val b = auth.upsert(Session("https://b.example", "me", "b"))
        val first = AccountContact(a.id, a.session.url, Contact("anna", "Мама"))
        val second = AccountContact(b.id, b.session.url, Contact("anna", "Аня", canCall = false))
        cache.replace(AccountContactPage(a.id, listOf(first)))
        cache.replace(AccountContactPage(b.id, listOf(second)))

        assertEquals(first, resolveContactCallTarget(auth, cache, first.peerKey)?.contact)
        assertFalse(requireNotNull(resolveContactCallTarget(auth, cache, second.peerKey)).contact.canCall)
        cache.remove(a, "anna")
        assertNull(resolveContactCallTarget(auth, cache, first.peerKey))
        assertEquals(second, resolveContactCallTarget(auth, cache, second.peerKey)?.contact)
        auth.remove(b.id)
        assertNull(resolveContactCallTarget(auth, cache, second.peerKey))
    }
}
