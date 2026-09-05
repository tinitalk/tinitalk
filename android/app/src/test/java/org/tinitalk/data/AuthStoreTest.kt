package org.tinitalk.data

import org.tinitalk.push.StoredWebPushConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthStoreTest {
    @Test
    fun clearsCachedAdminLabelWithoutLosingAccount() {
        val persistence = MemoryKeyValueStore()
        val store = AuthStore(persistence, PrefixTokenCipher())
        store.save(Session("https://a.example", "alice", "token"))
        val json = requireNotNull(persistence.get(AccountCollectionKey))
        persistence.put(AccountCollectionKey, json.replace("\"login\":\"alice\"", "\"login\":\"alice\",\"displayName\":\"ADMIN-ONLY\""))

        val account = store.list().single()

        assertNull(account.displayName)
        assertEquals("token", account.session.token)
        assertTrue(!requireNotNull(persistence.get(AccountCollectionKey)).contains("ADMIN-ONLY"))
    }

    @Test
    fun versionPointNineIgnoresLegacySingleAccountWithoutDeletingIt() {
        val persistence = MemoryKeyValueStore()
        val encrypted = PrefixTokenCipher().encrypt("legacy-token")
        persistence.put("url", "https://old.example")
        persistence.put("login", "alice")
        persistence.put("token", encrypted.value)
        persistence.put("iv", encrypted.iv)

        val accounts = AuthStore(persistence, PrefixTokenCipher()).list()

        assertTrue(accounts.isEmpty())
        assertEquals(encrypted.value, persistence.get("token"))
    }

    @Test
    fun acceptsServerAddressesWithoutHttpsPrefix() {
        assertEquals("https://talk.example.com", httpsServerUrl(" talk.example.com/ "))
        assertEquals("https://talk.example.com", httpsServerUrl("https://talk.example.com/"))
        assertEquals("https://talk.example.com", httpsServerUrl("HTTPS://TALK.EXAMPLE.COM:443/"))
        assertEquals("https://talk.example.com:8443", httpsServerUrl("TALK.EXAMPLE.COM:8443/"))
        assertEquals("https://talk_server.example.com", httpsServerUrl("HTTPS://TALK_SERVER.EXAMPLE.COM:443/"))
        assertNull(httpsServerUrl("https://"))
        assertNull(httpsServerUrl("http://talk.example.com"))
    }

    @Test
    fun rejectsAnotherAccountOnTheSameCanonicalServer() {
        val ids = ArrayDeque(listOf(AccountId("account-a"), AccountId("account-b")))
        val store = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher()) { ids.removeFirst() }
        val first = session("https://a.example", "alice", "session-a", "config-a")
        val duplicate = session("https://A.EXAMPLE:443/", "anna", "session-b", "config-b")
        store.add(store.newAccountId(), first, config(first))

        assertThrows(IllegalArgumentException::class.java) {
            store.add(store.newAccountId(), duplicate, config(duplicate))
        }

        assertEquals(listOf("alice"), store.list().map { it.session.login })
    }

    @Test
    fun storesTwoAccountsWithIndependentWebPushConfigurations() {
        val ids = ArrayDeque(listOf(AccountId("account-a"), AccountId("account-b")))
        val store = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher()) { ids.removeFirst() }
        val sessionA = session("https://a.example", "alice", "session-a", "config-a")
        val sessionB = session("https://b.example", "bob", "session-b", "config-b")
        val accountA = store.add(store.newAccountId(), sessionA, config(sessionA))
        val accountB = store.add(store.newAccountId(), sessionB, config(sessionB))

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
        val accountA = store.add(store.newAccountId(), sessionA, config(sessionA))
        val accountB = store.add(store.newAccountId(), sessionB, config(sessionB))

        assertTrue(store.removeIfCurrent(accountA.id, sessionA))

        assertNull(store.get(accountA.id))
        assertEquals(accountB, store.get(accountB.id))
    }

    @Test
    fun updatesMatchingServerWithoutReencryptingTokens() {
        val persistence = CountingKeyValueStore()
        val cipher = CountingTokenCipher()
        val ids = ArrayDeque(listOf(AccountId("account-a"), AccountId("account-b")))
        val store = AuthStore(persistence, cipher) { ids.removeFirst() }
        store.add(store.newAccountId(), session("https://a.example", "alice", "session-a", "config-a"), config(session("https://a.example", "alice", "session-a", "config-a")))
        store.add(store.newAccountId(), session("https://b.example", "bob", "session-b", "config-b"), config(session("https://b.example", "bob", "session-b", "config-b")))
        val before = AccountCollectionStorage.read(persistence).accounts.associate { it.id to it.token }
        persistence.resetWrites()
        cipher.reset()

        store.updateFeatures("https://a.example/", setOf("webpush_v1", "multi_account_v1"))

        val accounts = AccountCollectionStorage.read(persistence).accounts
        assertEquals(setOf("webpush_v1", "multi_account_v1"), accounts.single { it.id == "account-a" }.features)
        assertEquals(setOf("webpush_v1"), accounts.single { it.id == "account-b" }.features)
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
        store.add(store.newAccountId(), account, config(account))
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
