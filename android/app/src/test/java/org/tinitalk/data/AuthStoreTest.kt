package org.tinitalk.data

import org.tinitalk.push.StoredWebPushConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthStoreTest {
    @Test
    fun acceptsServerAddressesWithoutHttpsPrefix() {
        assertEquals("https://talk.example.com", httpsServerUrl(" talk.example.com/ "))
        assertEquals("https://talk.example.com", httpsServerUrl("https://talk.example.com/"))
        assertNull(httpsServerUrl("https://"))
        assertNull(httpsServerUrl("http://talk.example.com"))
    }

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

    @Test
    fun updatesEveryAccountOnMatchingServerWithoutReencryptingTokens() {
        val persistence = CountingKeyValueStore()
        val cipher = CountingTokenCipher()
        val ids = ArrayDeque(listOf(AccountId("account-a"), AccountId("account-b"), AccountId("account-c")))
        val store = AuthStore(persistence, cipher) { ids.removeFirst() }
        store.add(store.newAccountId(), session("https://a.example", "alice", "session-a", "config-a"), config(session("https://a.example", "alice", "session-a", "config-a")), "Alice")
        store.add(store.newAccountId(), session("https://a.example", "anna", "session-b", "config-a"), config(session("https://a.example", "anna", "session-b", "config-a")), "Anna")
        store.add(store.newAccountId(), session("https://b.example", "bob", "session-c", "config-b"), config(session("https://b.example", "bob", "session-c", "config-b")), "Bob")
        val before = AccountCollectionStorage.read(persistence).accounts.associate { it.id to it.token }
        persistence.resetWrites()
        cipher.reset()

        store.updateFeatures("https://a.example/", setOf("webpush_v1", "multi_account_v1"))

        val accounts = AccountCollectionStorage.read(persistence).accounts
        assertEquals(setOf("webpush_v1", "multi_account_v1"), accounts.single { it.id == "account-a" }.features)
        assertEquals(setOf("webpush_v1", "multi_account_v1"), accounts.single { it.id == "account-b" }.features)
        assertEquals(setOf("webpush_v1"), accounts.single { it.id == "account-c" }.features)
        assertEquals(before, accounts.associate { it.id to it.token })
        assertEquals(1, persistence.writes)
        assertEquals(0, cipher.encryptions)
        assertEquals(0, cipher.decryptions)
    }

    @Test
    fun unchangedFeaturesDoNotRewriteAccountStorage() {
        val persistence = CountingKeyValueStore()
        val cipher = CountingTokenCipher()
        val store = AuthStore(persistence, cipher) { AccountId("account-a") }
        val account = session("https://a.example", "alice", "session-a", "config-a")
        store.add(store.newAccountId(), account, config(account), "Alice")
        persistence.resetWrites()
        cipher.reset()

        store.updateFeatures("https://a.example", setOf("webpush_v1"))

        assertEquals(0, persistence.writes)
        assertEquals(0, cipher.encryptions)
        assertEquals(0, cipher.decryptions)
    }
}

private fun session(url: String, login: String, sessionId: String, configId: String) =
    Session(url, login, "token-$login", setOf("webpush_v1"), sessionId, configId)

private fun config(session: Session) = StoredWebPushConfig(
    serverUrl = session.url,
    vapidPublicKey = "BNVQmPpYlVnSqeE5_UfDgJQG4YIqq7FPPHUZ6riR5TqQh_9ZgfkrdmHH99yqCGMiMSRuOJ5hK3sLrx_cUpnF4U4",
    configId = requireNotNull(session.configId),
)

private class CountingKeyValueStore : KeyValueStore {
    private val delegate = MemoryKeyValueStore()
    var writes = 0
        private set

    override fun get(key: String): String? = delegate.get(key)

    override fun put(key: String, value: String) {
        writes++
        delegate.put(key, value)
    }

    override fun remove(vararg keys: String) = delegate.remove(*keys)

    override fun values(): List<String> = delegate.values()

    fun resetWrites() {
        writes = 0
    }
}

private class CountingTokenCipher : TokenCipher {
    private val delegate = PrefixTokenCipher()
    var encryptions = 0
        private set
    var decryptions = 0
        private set

    override fun encrypt(plain: String): CipherText {
        encryptions++
        return delegate.encrypt(plain)
    }

    override fun decrypt(cipherText: CipherText): String {
        decryptions++
        return delegate.decrypt(cipherText)
    }

    fun reset() {
        encryptions = 0
        decryptions = 0
    }
}
