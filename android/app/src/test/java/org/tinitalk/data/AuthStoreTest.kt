package org.tinitalk.data

import org.tinitalk.push.StoredWebPushConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthStoreTest {
    @Test
    fun passwordRecoveryKeepsOnlyLocalIdentityAcrossRestartAndReusesItOnSignIn() {
        val persistence = MemoryKeyValueStore()
        val store = AuthStore(persistence, PrefixTokenCipher())
        val old = Session("https://a.example", "alice", "revoked-token", sessionId = "old-session")
        val account = store.upsert(old)
        assertTrue(store.requireSignInIfCurrent(account.id, old))
        assertTrue(store.list().isEmpty())
        val json = requireNotNull(persistence.get(AccountCollectionKey))
        assertTrue(!json.contains("revoked-token"))
        assertTrue(!json.contains("old-session"))
        assertTrue(!json.contains("\"token\""))

        val restarted = AuthStore(persistence, PrefixTokenCipher())
        assertTrue(restarted.list().isEmpty())
        val otherLogin = restarted.upsert(Session(old.url, "bob", "bob-token"))
        val otherServer = restarted.upsert(Session("https://b.example", "alice", "alice-token"))
        assertTrue(otherLogin.id != account.id)
        assertTrue(otherServer.id != account.id)
        val restored = restarted.upsert(old.copy(url = "https://A.EXAMPLE:443/", token = "new-token", sessionId = "new-session"))
        assertEquals(account.id, restored.id)
        assertTrue(AccountCollectionStorage.read(persistence).signInRecoveries.isEmpty())
        assertEquals(3, restarted.list().size)
    }

    @Test
    fun stalePasswordFailureCannotReserveOrRemoveANewerAccount() {
        val persistence = MemoryKeyValueStore()
        val store = AuthStore(persistence, PrefixTokenCipher())
        val old = Session("https://a.example", "alice", "old-token", sessionId = "old-session")
        val account = store.upsert(old)
        val current = old.copy(token = "current-token", sessionId = "current-session")
        assertTrue(store.saveIfCurrent(account.id, old, current))
        assertTrue(!store.requireSignInIfCurrent(account.id, old))
        assertEquals(current, store.get(account.id)?.session)
        assertTrue(AccountCollectionStorage.read(persistence).signInRecoveries.isEmpty())
    }

    @Test
    fun accountCollectionWithoutRecoveryFieldRemainsReadable() {
        val persistence = MemoryKeyValueStore()
        val store = AuthStore(persistence, PrefixTokenCipher())
        val account = store.upsert(Session("https://a.example", "alice", "token"))
        persistence.put(AccountCollectionKey, requireNotNull(persistence.get(AccountCollectionKey))
            .replace(",\"signInRecoveries\":[]", ""))
        assertEquals(listOf(account), AuthStore(persistence, PrefixTokenCipher()).list())
    }

    @Test
    fun missingConstructorDefaultsDoNotInvalidateExistingAccounts() {
        val persistence = MemoryKeyValueStore()
        val store = AuthStore(persistence, PrefixTokenCipher())
        val first = store.upsert(Session("https://a.example", "alice", "token-a", sessionId = "session-a"))
        val second = store.upsert(Session("https://b.example", "bob", "token-b", sessionId = "session-b"))
        // Explicit null gives the same field value as a missing JSON field when
        // R8 removes the no-arg constructor and Gson allocates without defaults.
        persistence.put(AccountCollectionKey, requireNotNull(persistence.get(AccountCollectionKey))
            .replace("\"signInRecoveries\":[]", "\"signInRecoveries\":null"))

        val restarted = AuthStore(persistence, PrefixTokenCipher())
        assertEquals(listOf(first, second), restarted.list())
        assertTrue(AccountCollectionStorage.read(persistence).signInRecoveries.isEmpty())
        assertTrue(restarted.requireSignInIfCurrent(first.id, first.session))
        assertEquals(first.id, restarted.upsert(first.session.copy(token = "new-token")).id)
        assertEquals(second, restarted.get(second.id))
    }

    @Test
    fun legacyTokenWithoutSessionIsPendingButActualRevocationStillApplies() {
        val store = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher())
        val pending = Session("https://old.example", "alice", "legacy-token", features = setOf("webpush_v1"))
        val account = store.upsert(pending)
        assertTrue(pending.needsActivation())
        assertTrue(!store.invalidateIfCurrent(account.id, pending, AuthRemovalReason.SessionReplaced))
        assertEquals(pending, store.get(account.id)?.session)
        val activated = pending.copy(sessionId = "ready", configId = "config")
        assertTrue(store.saveIfCurrent(account.id, pending, activated))
        assertTrue(!activated.needsActivation())
        assertTrue(!store.invalidateIfCurrent(account.id, pending, AuthRemovalReason.SessionReplaced))
        assertTrue(store.invalidateIfCurrent(account.id, activated, AuthRemovalReason.SessionReplaced))
        store.upsert(pending)
        val retry = store.list().single()
        assertTrue(store.invalidateIfCurrent(retry.id, pending, AuthRemovalReason.Unauthorized))
    }

    @Test
    fun credentialRotationIgnoresOldSocketWhileResponseIsPendingAndReleasesGuardOnFailure() {
        val store = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher())
        val session = Session("https://a.example", "alice", "old-token", sessionId = "old-session")
        val account = store.upsert(session)
        assertThrows(IllegalStateException::class.java) {
            store.changingCredentials(account) {
                assertTrue(!store.invalidateIfCurrent(account.id, session))
                error("lost response")
            }
        }
        assertEquals(session, store.get(account.id)?.session)
        assertTrue(store.invalidateIfCurrent(account.id, session))
    }

    @Test
    fun unclaimedPasswordTokenSurvivesMissingSessionResponseButNotActualRevocation() {
        val store = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher())
        val session = Session("https://a.example", "alice", "issued", features = setOf(PASSWORD_AUTH_FEATURE))
        val account = store.upsert(session)
        assertTrue(!store.invalidateIfCurrent(account.id, session, AuthRemovalReason.SessionReplaced))
        assertEquals(session, store.get(account.id)?.session)
        assertTrue(store.invalidateIfCurrent(account.id, session, AuthRemovalReason.Unauthorized))
    }

    @Test
    fun staleSessionReplacementCannotClearRotatedPasswordSession() {
        val store = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher()) { AccountId("account-a") }
        val old = Session(
            "https://a.example",
            "alice",
            "old-token",
            setOf("webpush_v1", "password_auth_v1"),
            "old-session",
            "config-a",
        )
        val account = store.add(store.newAccountId(), old, config(old))
        val rotated = old.copy(token = "new-token", sessionId = "new-session")

        assertTrue(store.saveIfCurrent(account.id, old, rotated))
        assertTrue(!store.invalidateIfCurrent(account.id, old, AuthRemovalReason.SessionReplaced))

        assertEquals(rotated, store.get(account.id)?.session)
    }

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
