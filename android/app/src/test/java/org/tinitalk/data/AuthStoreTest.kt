package org.tinitalk.data

import org.tinitalk.push.StoredWebPushConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthStoreTest {
    @Test
    fun storesTwoAccountsWithIndependentWebPushConfigurations() {
        val ids = ArrayDeque(listOf(AccountId("account-a"), AccountId("account-b")))
        val store = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher()) { ids.removeFirst() }
        val sessionA = session("https://a.example", "alice", "session-a", "config-a")
        val sessionB = session("https://b.example", "bob", "session-b", "config-b")
        val accountA = store.add(store.newAccountId(), sessionA, config(sessionA), "Alice")
        val accountB = store.add(store.newAccountId(), sessionB, config(sessionB), "Bob")

        assertEquals(listOf(accountA, accountB), store.list())
        assertEquals(config(sessionA), store.webPushConfig(accountA.id))
        assertEquals(config(sessionB), store.webPushConfig(accountB.id))
    }

    @Test
    fun activatesWebPushOnlyForTheCurrentExistingSession() {
        val store = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher()) { AccountId("account-a") }
        val old = Session("https://a.example", "alice", "token", sessionId = "old")
        val account = store.upsert(old)
        val activated = old.copy(sessionId = "new", configId = "config-a")

        assertTrue(store.activateWebPushIfCurrent(account.id, old, activated, config(activated)))

        assertEquals(activated, store.get(account.id)?.session)
        assertEquals(config(activated), store.webPushConfig(account.id))
    }

    @Test
    fun removingOneAccountLeavesTheOtherUntouched() {
        val ids = ArrayDeque(listOf(AccountId("account-a"), AccountId("account-b")))
        val store = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher()) { ids.removeFirst() }
        val sessionA = session("https://a.example", "alice", "session-a", "config-a")
        val sessionB = session("https://b.example", "bob", "session-b", "config-b")
        val accountA = store.add(store.newAccountId(), sessionA, config(sessionA), "Alice")
        val accountB = store.add(store.newAccountId(), sessionB, config(sessionB), "Bob")

        assertTrue(store.removeIfCurrent(accountA.id, sessionA))

        assertNull(store.get(accountA.id))
        assertEquals(accountB, store.get(accountB.id))
    }
}

private fun session(url: String, login: String, sessionId: String, configId: String) =
    Session(url, login, "token-$login", setOf("webpush_v1"), sessionId, configId)

private fun config(session: Session) = StoredWebPushConfig(
    serverUrl = session.url,
    vapidPublicKey = "BNVQmPpYlVnSqeE5_UfDgJQG4YIqq7FPPHUZ6riR5TqQh_9ZgfkrdmHH99yqCGMiMSRuOJ5hK3sLrx_cUpnF4U4",
    configId = requireNotNull(session.configId),
)
