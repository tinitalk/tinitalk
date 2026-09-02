package org.tinitalk.data

import org.tinitalk.push.AccountWebPushRegistration
import org.tinitalk.push.StoredWebPushConfig
import org.tinitalk.push.WebPushClientConfig
import org.tinitalk.push.WebPushKeys
import org.tinitalk.push.WebPushSubscription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactRepositoryTest {
    @Test
    fun firstLoginClaimsSessionWithWebPushBeforeSavingAccount() {
        val auth = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher()) { AccountId("account-a") }
        val registration = RecordingWebPushRegistration()
        val cache = ContactCache(MemoryKeyValueStore())
        val api = RecordingApi(
            "alice",
            "config-a",
            contactPages = mapOf(
                "" to ContactPage(listOf(Contact("bob", "Bob")), "second"),
                "second" to ContactPage(listOf(Contact("carol", "Carol")), ""),
            ),
        )
        val repository = ContactRepository(
            auth,
            registration,
            contactCache = cache,
            apiFactory = { _, _, _, _ -> api },
        )

        val contacts = repository.signIn("a.example/", "alice", "token-a", "phone")

        assertEquals(listOf("bob", "carol"), contacts.items.map { it.login })
        assertEquals(listOf(AccountId("account-a")), registration.subscribed)
        assertEquals(subscription, api.claimedSubscription)
        assertEquals("phone", api.claimedDeviceId)
        assertEquals("session-alice", auth.list().single().session.sessionId)
        assertEquals("https://a.example", auth.webPushConfig(AccountId("account-a"))?.serverUrl)
        assertEquals(listOf("bob", "carol"), cache.load(auth.list().single()).items.map { it.login })
    }

    @Test
    fun addingSecondServerKeepsFirstAccountAndUsesAnotherPushInstance() {
        val ids = ArrayDeque(listOf(AccountId("account-a"), AccountId("account-b")))
        val auth = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher()) { ids.removeFirst() }
        val firstSession = Session(
            "https://a.example",
            "alice",
            "token-a",
            setOf("webpush_v1"),
            "session-a",
            "config-a",
        )
        val firstId = auth.newAccountId()
        auth.add(firstId, firstSession, storedConfig(firstSession), "Alice")
        val registration = RecordingWebPushRegistration()
        val cache = ContactCache(MemoryKeyValueStore())
        val api = RecordingApi(
            "bob",
            "config-b",
            contactPages = mapOf(
                "" to ContactPage(listOf(Contact("alice", "Alice")), "second"),
                "second" to ContactPage(listOf(Contact("carol", "Carol")), ""),
            ),
        )
        val repository = ContactRepository(
            auth,
            registration,
            contactCache = cache,
            apiFactory = { _, _, _, _ -> api },
        )

        val added = repository.addAccount("b.example", "bob", "token-b", "phone")

        assertEquals(listOf(firstId, added.account.id), auth.list().map { it.id })
        assertEquals(listOf("alice", "carol"), added.contacts.items.map { it.login })
        assertEquals(listOf("alice", "carol"), cache.load(added.account).items.map { it.login })
        assertEquals(listOf(added.account.id), registration.subscribed)
        assertEquals("https://b.example", auth.webPushConfig(added.account.id)?.serverUrl)
    }

    @Test
    fun addingAnotherLoginFromTheSameServerIsRejectedBeforePushRegistration() {
        val ids = ArrayDeque(listOf(AccountId("account-a"), AccountId("account-b")))
        val auth = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher()) { ids.removeFirst() }
        val first = Session("https://a.example", "alice", "token-a", setOf("webpush_v1"), "session-a", "config-a")
        auth.add(auth.newAccountId(), first, storedConfig(first), "Alice")
        val registration = RecordingWebPushRegistration()
        val repository = ContactRepository(
            auth,
            registration,
            apiFactory = { _, _, _, _ -> RecordingApi("anna", "config-a") },
        )

        assertThrows(DuplicateAccountException::class.java) {
            repository.addAccount("HTTPS://A.EXAMPLE:443/", "anna", "token-b", "phone")
        }

        assertTrue(registration.subscribed.isEmpty())
        assertEquals(listOf("alice"), auth.list().map { it.session.login })
    }

    @Test
    fun serverWithoutWebPushIsRejectedBeforeRegistration() {
        val ids = ArrayDeque(listOf(AccountId("account-a"), AccountId("account-b")))
        val auth = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher()) { ids.removeFirst() }
        val first = Session("https://a.example", "alice", "token", setOf("webpush_v1"), "s-a", "config-a")
        auth.add(auth.newAccountId(), first, storedConfig(first), "Alice")
        val registration = RecordingWebPushRegistration()
        val api = RecordingApi("bob", "config-b", features = emptySet())
        val repository = ContactRepository(auth, registration, apiFactory = { _, _, _, _ -> api })

        assertThrows(ServerCompatibilityException::class.java) {
            repository.addAccount("https://b.example", "bob", "token-b", "phone")
        }
        assertTrue(registration.subscribed.isEmpty())
    }

    @Test
    fun apiVersionFourIsTheCompatibilityBoundary() {
        for ((version, expected) in mapOf(
            3 to ServerCheckResult.ServerOutdated,
            4 to ServerCheckResult.Available,
            5 to ServerCheckResult.AppOutdated,
        )) {
            val repository = ContactRepository(
                AuthStore(MemoryKeyValueStore(), PrefixTokenCipher()),
                apiFactory = { _, _, _, _ -> RecordingApi("alice", "config", apiVersion = version) },
            )
            assertEquals(expected, repository.checkAddAccountServer("talk.example"))
        }
    }

    @Test
    fun refreshingAccountLoadsEveryContactPage() {
        val auth = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher())
        val cacheStore = MemoryKeyValueStore()
        val cache = ContactCache(cacheStore)
        val session = Session(
            "https://a.example",
            "alice",
            "token-a",
            setOf("webpush_v1"),
            "session-a",
            "config-a",
        )
        val accountId = auth.newAccountId()
        auth.add(accountId, session, storedConfig(session), "Alice")
        val api = RecordingApi(
            "alice",
            "config-a",
            contactPages = mapOf(
                "" to ContactPage(listOf(Contact("bob", "Bob")), "second"),
                "second" to ContactPage(listOf(Contact("carol", "Carol")), ""),
            ),
        )
        val repository = ContactRepository(auth, contactCache = cache, apiFactory = { _, _, _, _ -> api })

        val restored = requireNotNull(repository.refreshContacts(accountId))

        assertEquals(listOf("bob", "carol"), restored.items.map { it.login })
        assertEquals(
            listOf("bob", "carol"),
            ContactCache(cacheStore).load(requireNotNull(auth.get(accountId))).items.map { it.login },
        )
    }

    @Test
    fun failedContactSyncKeepsPreviousCompleteCache() {
        val auth = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher())
        val session = Session(
            "https://a.example",
            "alice",
            "token-a",
            setOf("webpush_v1"),
            "session-a",
            "config-a",
        )
        val accountId = auth.newAccountId()
        val account = auth.add(accountId, session, storedConfig(session), "Alice")
        val cache = ContactCache(MemoryKeyValueStore()).apply {
            replace(
                AccountContactPage(
                    accountId,
                    listOf(AccountContact(accountId, session.url, Contact("old", "Old"))),
                ),
            )
        }
        val api = RecordingApi(
            "alice",
            "config-a",
            contactPages = mapOf(
                "" to ContactPage(listOf(Contact("new", "New")), "second"),
            ),
            failedCursor = "second",
        )
        val repository = ContactRepository(auth, contactCache = cache, apiFactory = { _, _, _, _ -> api })

        assertThrows(IllegalStateException::class.java) { repository.refreshContacts(accountId) }

        assertEquals(listOf("old"), cache.load(account).items.map { it.login })
    }

    @Test
    fun renamingContactUpdatesCachedSnapshot() {
        val auth = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher())
        val session = Session("https://a.example", "alice", "token-a")
        val account = auth.upsert(session)
        val cache = ContactCache(MemoryKeyValueStore()).apply {
            replace(
                AccountContactPage(
                    account.id,
                    listOf(AccountContact(account.id, session.url, Contact("bob", "Bob"))),
                ),
            )
        }
        val repository = ContactRepository(
            auth,
            contactCache = cache,
            apiFactory = { _, _, _, _ -> RecordingApi("alice", "config-a") },
        )

        repository.updateContactName(account.id, "bob", "Bobby")

        assertEquals(listOf("Bobby"), cache.load(account).items.map { it.displayName })
    }

    @Test
    fun removingAccountDeletesItsCachedContacts() {
        val auth = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher())
        val session = Session("https://a.example", "alice", "token-a")
        val account = auth.upsert(session)
        val cache = ContactCache(MemoryKeyValueStore()).apply {
            replace(
                AccountContactPage(
                    account.id,
                    listOf(AccountContact(account.id, session.url, Contact("bob", "Bob"))),
                ),
            )
        }
        val repository = ContactRepository(auth, contactCache = cache)

        repository.removeAccount(account.id)

        assertTrue(cache.load(account).items.isEmpty())
    }

    @Test
    fun explicitRemoveAccountInvokesCommonAndExplicitCallbacksOnce() {
        val auth = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher())
        val session = Session("https://a.example", "alice", "token-a")
        val account = auth.upsert(session)
        val common = mutableListOf<Pair<AccountId, Session>>()
        val explicit = mutableListOf<Pair<AccountId, Session>>()
        val repository = ContactRepository(
            authStore = auth,
            onAccountRemoved = { accountId, removedSession -> common += accountId to removedSession },
            onExplicitAccountRemoved = { accountId, removedSession -> explicit += accountId to removedSession },
        )

        assertTrue(repository.removeAccount(account.id))

        assertEquals(listOf(account.id to session), common)
        assertEquals(listOf(account.id to session), explicit)
    }

    @Test
    fun staleRemoveAccountDoesNotInvokeExplicitCallback() {
        val auth = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher())
        val repository = ContactRepository(
            authStore = auth,
            onExplicitAccountRemoved = { _, _ -> error("stale account must not trigger explicit cleanup") },
        )

        assertFalse(repository.removeAccount(AccountId("missing")))
    }

    @Test
    fun unauthorizedRemovalInvokesCommonCallbackButNotExplicitCallback() {
        val auth = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher())
        val session = Session("https://a.example", "alice", "token-a")
        val account = auth.upsert(session)
        val common = mutableListOf<Pair<AccountId, Session>>()
        val explicit = mutableListOf<Pair<AccountId, Session>>()
        val repository = ContactRepository(
            authStore = auth,
            onAccountRemoved = { accountId, removedSession -> common += accountId to removedSession },
            onExplicitAccountRemoved = { accountId, removedSession -> explicit += accountId to removedSession },
            apiFactory = { _, _, _, _ -> RecordingApi("alice", "config-a", failContactsWith = ApiException(401, "unauthorized")) },
        )

        assertThrows(ApiException::class.java) {
            repository.refreshContacts(account.id)
        }

        assertEquals(listOf(account.id to session), common)
        assertTrue(explicit.isEmpty())
        assertTrue(auth.list().isEmpty())
    }
}

private val subscription = WebPushSubscription(
    "https://fcm.googleapis.com/fcm/send/test",
    WebPushKeys("p256dh", "auth"),
)

private class RecordingWebPushRegistration : AccountWebPushRegistration {
    val subscribed = mutableListOf<AccountId>()

    override fun subscribe(accountId: AccountId, config: StoredWebPushConfig): WebPushSubscription {
        subscribed += accountId
        return subscription
    }

    override fun restore(accountId: AccountId, config: StoredWebPushConfig) = Unit
    override fun unsubscribe(accountId: AccountId) = Unit
}

private class RecordingApi(
    private val login: String,
    private val configId: String,
    private val apiVersion: Int = 4,
    private val features: Set<String> = setOf("webpush_v1"),
    private val contactPages: Map<String, ContactPage>? = null,
    private val failedCursor: String? = null,
    private val failContactsWith: RuntimeException? = null,
) : HouseholdApi {
    var claimedDeviceId: String? = null
    var claimedSubscription: WebPushSubscription? = null

    override fun serverInfo() = ServerInfo("tinitalk", "ok", apiVersion, features = features)
    override fun webPushConfig() = WebPushClientConfig(
        "BNVQmPpYlVnSqeE5_UfDgJQG4YIqq7FPPHUZ6riR5TqQh_9ZgfkrdmHH99yqCGMiMSRuOJ5hK3sLrx_cUpnF4U4",
        configId,
    )
    override fun me() = Profile(login, login.replaceFirstChar(Char::uppercase))
    override fun contactsPage(limit: Int, cursor: String): ContactPage {
        failContactsWith?.let { throw it }
        if (cursor == failedCursor) error("page failed")
        return contactPages?.getValue(cursor) ?: ContactPage(
            listOf(Contact(login, login), Contact("bob", "Bob")),
            "",
        )
    }
    override fun claimSession(deviceId: String, subscription: WebPushSubscription, configId: String): String {
        claimedDeviceId = deviceId
        claimedSubscription = subscription
        return "session-$login"
    }
    override fun updateContactName(login: String, customName: String?) = Contact(login, customName.orEmpty())
    override fun calls(limit: Int, before: Long, peerLogin: String?) = CallHistoryPage(emptyList(), 0, 0, 0)
    override fun markCallsRead(throughId: Long, peerLogin: String?) = CallUnreadState(0, emptyList())
    override fun putDevice(deviceId: String, subscription: WebPushSubscription, configId: String) = Unit
}

private fun storedConfig(session: Session) = StoredWebPushConfig(
    session.url,
    "BNVQmPpYlVnSqeE5_UfDgJQG4YIqq7FPPHUZ6riR5TqQh_9ZgfkrdmHH99yqCGMiMSRuOJ5hK3sLrx_cUpnF4U4",
    requireNotNull(session.configId),
)
