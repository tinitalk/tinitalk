package org.tinitalk.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContactRepositoryTest {
    @Test
    fun signInPersistsObservedServerFeatures() {
        val store = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher())
        val api = FakeApiClient(serverInfo = ServerInfo("tinitalk", "ok", 3, features = setOf("video_1to1")))
        val repo = ContactRepository(store) { _, _, _ -> api }

        repo.signIn("https://host", "alice", "token")

        assertEquals(setOf("video_1to1"), store.load()?.features)
        assertEquals(0, api.claimRequests)
    }

    @Test
    fun manualSignInClaimsAdvertisedSingleDeviceSession() {
        val store = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher())
        val api = FakeApiClient(
            serverInfo = ServerInfo("tinitalk", "ok", 3, features = setOf("single_device_session")),
            claimedSessionId = "session-123",
        )
        val repo = ContactRepository(store) { _, _, _ -> api }

        repo.signIn("https://host", "alice", "token", deviceId = "android-device")

        assertEquals(1, api.claimRequests)
        assertEquals("android-device", api.claimedDeviceId)
        assertEquals("session-123", store.load()?.sessionId)
    }

    @Test
    fun newClientSilentlyClaimsLegacySessionDuringRestore() {
        val store = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher())
        store.save(Session("https://host", "alice", "token"))
        val api = FakeApiClient(
            serverInfo = ServerInfo("tinitalk", "ok", 3, features = setOf("single_device_session")),
            claimedSessionId = "session-restored",
        )
        val repo = ContactRepository(store) { _, _, _ -> api }

        repo.restoreContacts(deviceId = "android-device")

        assertEquals(1, api.claimRequests)
        assertEquals("android-device", api.claimedDeviceId)
        assertEquals("session-restored", store.load()?.sessionId)
    }

    @Test
    fun restorePersistsClaimBeforeLaterNetworkFailure() {
        val store = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher())
        store.save(Session("https://host", "alice", "token"))
        val api = FakeApiClient(
            serverInfo = ServerInfo("tinitalk", "ok", 3, features = setOf("single_device_session")),
            claimedSessionId = "session-restored",
            error = ApiException(503, "offline"),
        )
        val repo = ContactRepository(store) { _, _, _ -> api }

        runCatching { repo.restoreContacts(deviceId = "android-device") }

        assertEquals("session-restored", store.load()?.sessionId)
    }

    @Test
    fun restoreBuildsAuthenticatedApiWithPersistedSessionId() {
        val store = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher())
        store.save(Session("https://host", "alice", "token", sessionId = "session-123"))
        val seenSessionIds = mutableListOf<String?>()
        val api = FakeApiClient()
        val repo = ContactRepository(
            store,
            apiFactory = { _, _, _, sessionId ->
                seenSessionIds += sessionId
                api
            },
        )

        repo.restoreContacts()

        assertEquals(listOf("session-123"), seenSessionIds)
        assertEquals(0, api.claimRequests)
    }

    @Test
    fun restorePersistsFeaturesObservedAfterLegacySessionLoad() {
        val values = MemoryKeyValueStore().apply {
            put("url", "https://host")
            put("login", "alice")
            put("token", "nekot")
            put("iv", "iv")
        }
        val store = AuthStore(values, PrefixTokenCipher())
        val repo = ContactRepository(store) { _, _, _ ->
            FakeApiClient(serverInfo = ServerInfo("tinitalk", "ok", 3, features = setOf("video_1to1")))
        }

        repo.restoreContacts()

        assertEquals(setOf("video_1to1"), store.load()?.features)
    }

    @Test
    fun successfulCheckRefreshesFeaturesForMatchingStoredServer() {
        val store = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher())
        store.save(Session("https://host", "alice", "token", setOf("stale_feature")))
        val repo = ContactRepository(store) { _, _, _ ->
            FakeApiClient(serverInfo = ServerInfo("tinitalk", "ok", 3, features = setOf("video_1to1")))
        }

        assertEquals(ServerCheckResult.Available, repo.checkServer("https://host/"))

        assertEquals(setOf("video_1to1"), store.load()?.features)
    }

    @Test
    fun reportsServerApiAndCommitDetails() {
        val repo = ContactRepository(AuthStore(MemoryKeyValueStore(), PrefixTokenCipher())) { _, _, _ ->
            FakeApiClient(serverInfo = ServerInfo("tinitalk", "ok", 3, commit = "01234567"))
        }

        assertEquals(
            ServerCheckDetails(ServerCheckResult.Available, apiVersion = 3, commit = "01234567"),
            repo.checkServerDetails("https://host"),
        )
    }

    @Test
    fun reportsServerAddressHealthWithoutStartingAuthentication() {
        val cases = listOf(
            ServerInfo("tinitalk", "ok", 3) to ServerCheckResult.Available,
            ServerInfo("another-service", "ok", 3) to ServerCheckResult.WrongServer,
            ServerInfo("tinitalk", "ok", 2) to ServerCheckResult.ServerOutdated,
            ServerInfo("tinitalk", "ok", 4) to ServerCheckResult.AppOutdated,
            ServerInfo("tinitalk", "maintenance", 3) to ServerCheckResult.Unavailable,
        )

        cases.forEach { (serverInfo, expected) ->
            val store = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher())
            val api = FakeApiClient(serverInfo = serverInfo)
            val repo = ContactRepository(store) { _, _, _ -> api }

            assertEquals(expected, repo.checkServer(" https://host/ "))
            assertEquals(0, api.profileRequests)
            assertNull(store.load())
        }
    }

    @Test
    fun reportsUnavailableWhenServerCheckCannotConnect() {
        val repo = ContactRepository(AuthStore(MemoryKeyValueStore(), PrefixTokenCipher())) { _, _, _ ->
            FakeApiClient(serverInfoError = IllegalStateException("offline"))
        }

        assertEquals(ServerCheckResult.Unavailable, repo.checkServer("https://host"))
    }

    @Test
    fun rejectsWrongServerOrIncompatibleApiBeforeAuthentication() {
        val cases = listOf(
            ServerInfo("another-service", "ok", 3) to CompatibilityProblem.WrongServer,
            ServerInfo("tinitalk", "ok", 2) to CompatibilityProblem.ServerOutdated,
            ServerInfo("tinitalk", "ok", 4) to CompatibilityProblem.AppOutdated,
        )

        cases.forEach { (serverInfo, expectedProblem) ->
            val store = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher())
            val api = FakeApiClient(serverInfo = serverInfo)
            val repo = ContactRepository(store) { _, _, _ -> api }

            val error = runCatching {
                repo.signIn("https://host", "alice", "token")
            }.exceptionOrNull() as? ServerCompatibilityException

            assertEquals(expectedProblem, error?.problem)
            assertEquals(0, api.profileRequests)
            assertNull(store.load())
        }
    }

    @Test
    fun checksServerCompatibilityWhenRestoringSavedSession() {
        val store = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher())
        store.save(Session("https://host", "alice", "token"))
        val api = FakeApiClient(serverInfo = ServerInfo("tinitalk", "ok", 2))
        val repo = ContactRepository(store) { _, _, _ -> api }

        val error = runCatching { repo.restoreContacts() }
            .exceptionOrNull() as? ServerCompatibilityException

        assertEquals(CompatibilityProblem.ServerOutdated, error?.problem)
        assertEquals(0, api.profileRequests)
    }

    @Test
    fun verifiesCredentialsBeforeSavingAndFiltersSelf() {
        val store = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher())
        val api = FakeApiClient(
            profile = Profile("alice", "Alice"),
            contacts = listOf(Contact("alice", "Alice"), Contact("bob", "Bob")),
        )
        val repo = ContactRepository(store) { _, _, _ -> api }

        val page = repo.signIn("https://host", "alice", "token")

        assertEquals(listOf(Contact("bob", "Bob")), page.items)
        assertEquals(Session("https://host", "alice", "token"), store.load())
    }

    @Test
    fun keepsServerContactOrder() {
        val repo = ContactRepository(AuthStore(MemoryKeyValueStore(), PrefixTokenCipher())) { _, _, _ ->
            FakeApiClient(
                profile = Profile("self", "Я"),
                contacts = listOf(
                    Contact("anna", "Яна"),
                    Contact("maria", "мария"),
                    Contact("zoe", "Анна"),
                ),
            )
        }

        val page = repo.signIn("https://host", "self", "token")

        assertEquals(listOf("Яна", "мария", "Анна"), page.items.map(Contact::displayName))
    }

    @Test
    fun trimsManualCredentialsBeforeAuthenticatingAndSaving() {
        val store = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher())
        var captured: Session? = null
        val repo = ContactRepository(store) { url, login, token ->
            captured = Session(url, login, token)
            FakeApiClient(profile = Profile("alex", "Alex"))
        }

        repo.signIn(" https://tinitalk.example.com/ ", " alex ", " token\n")

        val expected = Session("https://tinitalk.example.com", "alex", "token")
        assertEquals(expected, captured)
        assertEquals(expected, store.load())
    }

    @Test
    fun invalidTokenClearsSession() {
        val store = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher())
        store.save(Session("https://host", "alice", "bad"))
        val repo = ContactRepository(store) { _, _, _ -> FakeApiClient(error = ApiException(401, "unauthorized")) }

        val result = runCatching { repo.signIn("https://host", "alice", "bad") }

        assertEquals(true, result.isFailure)
        assertNull(store.load())
    }

    @Test
    fun invalidTokenClearsFeatureBearingSessionWithSameCredentials() {
        val store = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher())
        store.save(Session("https://host", "alice", "bad", setOf("video_1to1")))
        val repo = ContactRepository(store) { _, _, _ -> FakeApiClient(error = ApiException(401, "unauthorized")) }

        val result = runCatching { repo.signIn("https://host", "alice", "bad") }

        assertEquals(true, result.isFailure)
        assertNull(store.load())
    }

    @Test
    fun staleUnauthorizedDoesNotClearNewSession() {
        val store = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher())
        val oldSession = Session("https://host", "alice", "old")
        val newSession = Session("https://host", "bob", "new")
        store.save(oldSession)
        val repo = ContactRepository(store) { _, _, _ ->
            FakeApiClient(
                error = ApiException(401, "unauthorized"),
                beforeError = { store.save(newSession) },
            )
        }

        runCatching { repo.restoreContacts() }

        assertEquals(newSession, store.load())
    }

    @Test
    fun staleReplacementUnauthorizedDoesNotInvalidateNewSession() {
        AuthSessionEvents.clear()
        val store = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher())
        val oldSession = Session("https://host", "alice", "old", sessionId = "old-session")
        val newSession = Session("https://host", "alice", "new", sessionId = "new-session")
        store.save(oldSession)
        val events = mutableListOf<AuthSessionEvent>()
        val observer: (AuthSessionEvent) -> Unit = events::add
        AuthSessionEvents.observe(observer)
        try {
            val repo = ContactRepository(store) { _, _, _ ->
                FakeApiClient(
                    error = ApiException(401, "replaced", SessionReplacedReason),
                    beforeError = { store.save(newSession) },
                )
            }

            runCatching { repo.restoreContacts() }

            assertEquals(newSession, store.load())
            assertEquals(emptyList<AuthSessionEvent>(), events)
        } finally {
            AuthSessionEvents.removeObserver(observer)
            AuthSessionEvents.clear()
        }
    }

    @Test
    fun currentReplacementUnauthorizedPublishesInvalidation() {
        AuthSessionEvents.clear()
        val store = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher())
        val session = Session("https://host", "alice", "token", sessionId = "session-123")
        store.save(session)
        val events = mutableListOf<AuthSessionEvent>()
        val observer: (AuthSessionEvent) -> Unit = events::add
        AuthSessionEvents.observe(observer)
        try {
            val repo = ContactRepository(store) { _, _, _ ->
                FakeApiClient(error = ApiException(401, "replaced", SessionReplacedReason))
            }

            runCatching { repo.restoreContacts() }

            assertNull(store.load())
            assertEquals(listOf(AuthSessionEvent(session)), events)
        } finally {
            AuthSessionEvents.removeObserver(observer)
            AuthSessionEvents.clear()
        }
    }

    @Test
    fun signOutClearsSavedSession() {
        val store = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher())
        store.save(Session("https://host", "alice", "token"))
        val repo = ContactRepository(store) { _, _, _ -> FakeApiClient() }

        repo.signOut()

        assertNull(store.load())
    }

    @Test
    fun loadsCallHistoryUsingSavedSession() {
        val store = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher())
        store.save(Session("https://host", "alice", "token"))
        val expected = CallHistoryPage(
            items = listOf(CallHistoryItem(7, "bob", "Bob", "outgoing", "completed", true, 1787740200, 65)),
            nextBefore = 5,
            latestId = 7,
            unreadMissedCount = 1,
        )
        val api = FakeApiClient(callHistory = expected)
        val repo = ContactRepository(store) { _, _, _ -> api }

        assertEquals(expected, repo.loadCallHistory(peerLogin = "bob"))
        assertEquals("bob", api.requestedPeer)
    }

    @Test
    fun refreshesContactsWithoutReloadingProfile() {
        val store = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher())
        store.save(Session("https://host", "alice", "token"))
        val api = FakeApiClient(
            contacts = listOf(
                Contact("alice", "Alice"),
                Contact("bob", "Bob"),
            ),
        )
        val repo = ContactRepository(store) { _, _, _ -> api }

        val page = repo.refreshContacts(cursor = "next-page")

        assertEquals(listOf("bob"), page?.items?.map(Contact::login))
        assertEquals(0, api.profileRequests)
        assertEquals(1, api.contactsRequests)
        assertEquals("next-page", api.requestedContactCursor)
    }

    @Test
    fun updatesContactAndMarksOnlyItsHistoryRead() {
        val store = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher())
        store.save(Session("https://host", "alice", "token"))
        val updated = Contact("bob", "Мама", "Bob", "Мама")
        val unreadAfterRead = CallUnreadState(2, listOf(UnreadMissedContact("carol", 1_787_743_800)))
        val api = FakeApiClient(updatedContact = updated, unreadAfterRead = unreadAfterRead)
        val repo = ContactRepository(store) { _, _, _ -> api }

        assertEquals(updated, repo.updateContactName("bob", "Мама"))
        assertEquals(unreadAfterRead, repo.markCallHistoryRead(42, peerLogin = "bob"))
        assertEquals("bob", api.updatedLogin)
        assertEquals("Мама", api.updatedName)
        assertEquals("bob", api.readPeer)
    }
}

private class FakeApiClient(
    private val serverInfo: ServerInfo = ServerInfo("tinitalk", "ok", 3),
    private val serverInfoError: RuntimeException? = null,
    private val profile: Profile = Profile("alice", "Alice"),
    private val contacts: List<Contact> = emptyList(),
    private val callHistory: CallHistoryPage = CallHistoryPage(emptyList(), 0, 0, 0),
    private val updatedContact: Contact = Contact("bob", "Bob"),
    private val unreadAfterRead: CallUnreadState = CallUnreadState(0, emptyList()),
    private val error: RuntimeException? = null,
    private val beforeError: (() -> Unit)? = null,
    private val claimedSessionId: String = "session-id",
) : HouseholdApi {
    var profileRequests = 0
    var contactsRequests = 0
    var requestedContactCursor = ""
    var requestedPeer: String? = null
    var updatedLogin: String? = null
    var updatedName: String? = null
    var readPeer: String? = null
    var claimRequests = 0
    var claimedDeviceId: String? = null

    override fun serverInfo(): ServerInfo = serverInfoError?.let { throw it } ?: serverInfo

    override fun me(): Profile {
        profileRequests++
        error?.let {
            beforeError?.invoke()
            throw it
        }
        return profile
    }

    override fun contactsPage(limit: Int, cursor: String): ContactPage {
        contactsRequests++
        requestedContactCursor = cursor
        error?.let { throw it }
        return ContactPage(contacts, "")
    }

    override fun updateContactName(login: String, customName: String?): Contact {
        updatedLogin = login
        updatedName = customName
        return updatedContact
    }

    override fun calls(limit: Int, before: Long, peerLogin: String?): CallHistoryPage {
        requestedPeer = peerLogin
        return callHistory
    }

    override fun markCallsRead(throughId: Long, peerLogin: String?): CallUnreadState {
        readPeer = peerLogin
        return unreadAfterRead
    }

    override fun putDevice(deviceId: String, fcmToken: String) = Unit

    override fun claimSession(deviceId: String): String {
        claimRequests++
        claimedDeviceId = deviceId
        return claimedSessionId
    }
}
