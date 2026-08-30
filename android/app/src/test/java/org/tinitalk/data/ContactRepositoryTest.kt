package org.tinitalk.data

import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseOptions
import org.tinitalk.push.FirebaseBootstrap
import org.tinitalk.push.FirebaseClientConfig
import org.tinitalk.push.FirebaseConfigPersistence
import org.tinitalk.push.FirebaseConfigStore
import org.tinitalk.push.FirebaseRegistration
import org.tinitalk.push.FirebaseRuntime
import org.tinitalk.push.StoredFirebaseConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ContactRepositoryTest {
    @Test
    fun activationFailuresStopAtTheirBoundaryWithoutSavingASession() {
        val cases = listOf(
            FailureCase(Failure.MissingFeature, listOf("health"), ServerCompatibilityException::class.java),
            FailureCase(Failure.ConfigFetch, listOf("health", "firebase-config"), ApiException::class.java),
            FailureCase(
                Failure.ConfigSave,
                listOf("health", "firebase-config", "save-config", "save-config"),
                IllegalStateException::class.java,
            ),
            FailureCase(
                Failure.Register,
                listOf("health", "firebase-config", "save-config", "bootstrap", "register"),
                Exception::class.java,
            ),
            FailureCase(
                Failure.InstallationId,
                listOf("health", "firebase-config", "save-config", "bootstrap", "register", "installation-id"),
                Exception::class.java,
            ),
            FailureCase(
                Failure.Claim,
                listOf(
                    "health", "firebase-config", "save-config", "bootstrap", "register", "installation-id",
                    "claim-session",
                ),
                ApiException::class.java,
            ),
            FailureCase(
                Failure.StaleClaim,
                listOf(
                    "health", "firebase-config", "save-config", "bootstrap", "register", "installation-id",
                    "claim-session",
                ),
                ApiException::class.java,
            ),
        )

        cases.forEach { case ->
            val fixture = RepositoryFixture(case.failure)

            val error = runCatching {
                onRepositoryThread {
                    fixture.repository.signIn("https://host.example/", "alice", "token", "android-device")
                }
            }.exceptionOrNull()

            assertTrue("${case.failure}: $error", case.errorType.isInstance(error))
            assertEquals(case.failure.toString(), case.expectedTrace, fixture.trace)
            assertNull(fixture.authStore.load())
            assertEquals(0, fixture.api.profileRequests)
            assertEquals(0, fixture.api.contactsRequests)
        }
    }

    @Test
    fun configurationMismatchPersistsOnlyPublicConfigAndRequiresRestart() {
        val fixture = RepositoryFixture(existingOptions = firebaseOptions(projectId = "already-active-project"))

        val error = runCatching {
            onRepositoryThread {
                fixture.repository.signIn("https://host.example", "alice", "entered-token", "android-device")
            }
        }.exceptionOrNull()

        assertTrue(error is FirebaseConfigurationRestartRequiredException)
        assertEquals(listOf("health", "firebase-config", "save-config", "bootstrap"), fixture.trace)
        assertEquals("sha256:config", fixture.configStore.load()?.configId)
        assertNull(fixture.authStore.load())
    }

    @Test
    fun activatesWithoutPermissionInputAndLoadsHomeDataInExactOrder() {
        val fixture = RepositoryFixture()

        val page = onRepositoryThread {
            fixture.repository.signIn(
                " https://host.example/// ",
                " alice ",
                " token\n",
                "android-device",
            )
        }

        assertEquals(
            listOf(
                "health", "firebase-config", "save-config", "bootstrap", "register", "installation-id",
                "claim-session", "save-session", "persist-registration", "me", "contacts",
            ),
            fixture.trace,
        )
        assertEquals(listOf(Contact("bob", "Bob")), page.items)
        assertEquals(
            Session(
                "https://host.example",
                "alice",
                "token",
                features = setOf("dynamic_fcm_v1", "single_device_session"),
                sessionId = "session-123",
                configId = "sha256:config",
            ),
            fixture.authStore.load(),
        )
        assertEquals(ActivationRequest("android-device", "fid-123", "sha256:config"), fixture.api.claim)
    }

    @Test
    fun serverConfirmedSessionRemainsSavedWhenLaterHomeDataLoadFails() {
        val fixture = RepositoryFixture(failure = Failure.Profile)

        val result = runCatching {
            onRepositoryThread {
                fixture.repository.signIn("https://host.example", "alice", "token", "android-device")
            }
        }

        assertTrue(result.isFailure)
        assertEquals("session-123", fixture.authStore.load()?.sessionId)
        assertEquals("sha256:config", fixture.authStore.load()?.configId)
        assertEquals(0, fixture.api.contactsRequests)
    }

    @Test
    fun restoresOnlyASessionBoundToTheStoredNormalizedServerAndConfig() {
        val bound = Session(
            "https://host.example/",
            "alice",
            "token",
            features = setOf("dynamic_fcm_v1"),
            sessionId = "session-123",
            configId = "sha256:config",
        )
        val cases = listOf(
            Triple("matching", bound, storedConfig("https://host.example", "sha256:config")),
            Triple("legacy", bound.copy(configId = null), storedConfig("https://host.example", "sha256:config")),
            Triple("url mismatch", bound, storedConfig("https://other.example", "sha256:config")),
            Triple("config mismatch", bound, storedConfig("https://host.example", "sha256:other")),
            Triple("missing config", bound, null),
        )

        cases.forEach { (name, session, config) ->
            val store = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher()).apply { save(session) }
            val trace = mutableListOf<String>()
            val configStore = firebaseConfigStore(config)
            val api = FakeApiClient(trace = trace)
            val repository = repository(store, configStore, api)

            val contacts = repository.restoreContacts()

            if (name == "matching") {
                assertEquals(listOf(Contact("bob", "Bob")), contacts?.items)
                assertEquals(listOf("health", "me", "contacts"), trace)
            } else {
                assertNull(name, contacts)
                assertEquals(name, emptyList<String>(), trace)
            }
        }
    }

    @Test
    fun restoreRejectsServerThatNoLongerAdvertisesDynamicFirebase() {
        val session = Session(
            "https://host.example",
            "alice",
            "token",
            sessionId = "session-123",
            configId = "sha256:config",
        )
        val store = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher()).apply { save(session) }
        val api = FakeApiClient(serverInfo = healthyInfo(features = emptySet()))
        val repository = repository(
            store,
            firebaseConfigStore(storedConfig("https://host.example", "sha256:config")),
            api,
        )

        val error = runCatching { repository.restoreContacts() }.exceptionOrNull()

        assertTrue(error is ServerCompatibilityException)
        assertEquals(0, api.profileRequests)
    }

    @Test
    fun reportsServerAddressHealthWithoutStartingAuthentication() {
        val cases = listOf(
            healthyInfo() to ServerCheckResult.Available,
            ServerInfo("another-service", "ok", 3) to ServerCheckResult.WrongServer,
            ServerInfo("tinitalk", "ok", 2) to ServerCheckResult.ServerOutdated,
            ServerInfo("tinitalk", "ok", 4) to ServerCheckResult.AppOutdated,
            ServerInfo("tinitalk", "maintenance", 3) to ServerCheckResult.Unavailable,
        )

        cases.forEach { (serverInfo, expected) ->
            val store = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher())
            val api = FakeApiClient(serverInfo = serverInfo)
            val repository = ContactRepository(store) { _, _, _ -> api }

            assertEquals(expected, repository.checkServer(" https://host.example/ "))
            assertEquals(0, api.profileRequests)
            assertNull(store.load())
        }
    }

    @Test
    fun staleUnauthorizedRestoreDoesNotClearAReplacementSession() {
        val config = storedConfig("https://host.example", "sha256:config")
        val store = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher())
        val old = Session(
            "https://host.example",
            "alice",
            "old",
            sessionId = "old-session",
            configId = config.configId,
        )
        val replacement = old.copy(token = "new", sessionId = "new-session")
        store.save(old)
        val api = FakeApiClient(
            error = ApiException(401, "unauthorized"),
            beforeError = { store.save(replacement) },
        )
        val repository = repository(store, firebaseConfigStore(config), api)

        runCatching { repository.restoreContacts() }

        assertEquals(replacement, store.load())
    }

    @Test
    fun currentReplacementUnauthorizedPublishesInvalidation() {
        AuthSessionEvents.clear()
        val config = storedConfig("https://host.example", "sha256:config")
        val store = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher())
        val session = Session(
            "https://host.example",
            "alice",
            "token",
            sessionId = "session-123",
            configId = config.configId,
        )
        store.save(session)
        val events = mutableListOf<AuthSessionEvent>()
        val observer: (AuthSessionEvent) -> Unit = events::add
        AuthSessionEvents.observe(observer)
        try {
            val api = FakeApiClient(error = ApiException(401, "replaced", SessionReplacedReason))
            val repository = repository(store, firebaseConfigStore(config), api)

            runCatching { repository.restoreContacts() }

            assertNull(store.load())
            assertEquals(listOf(AuthSessionEvent(session)), events)
        } finally {
            AuthSessionEvents.removeObserver(observer)
            AuthSessionEvents.clear()
        }
    }

    @Test
    fun signOutAndAuthenticatedDataOperationsKeepExistingBehavior() {
        val store = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher())
        store.save(Session("https://host", "alice", "token", sessionId = "session-123", configId = "config"))
        val history = CallHistoryPage(
            listOf(CallHistoryItem(7, "bob", "Bob", "outgoing", "completed", true, 1787740200, 65)),
            5,
            7,
            1,
        )
        val api = FakeApiClient(callHistory = history)
        val repository = ContactRepository(store) { _, _, _ -> api }

        assertEquals(history, repository.loadCallHistory(peerLogin = "bob"))
        assertEquals("bob", api.requestedPeer)
        repository.signOut()
        assertNull(store.load())
    }

    private fun repository(
        store: AuthStore,
        configStore: FirebaseConfigStore,
        api: FakeApiClient,
    ) = ContactRepository(
        authStore = store,
        firebaseConfigStore = configStore,
        firebaseBootstrap = FirebaseBootstrap(configStore, NoOpFirebaseRuntime),
        firebaseRegistration = FirebaseRegistration(
            register = { Tasks.forResult(null) },
            installationId = { Tasks.forResult("fid-123") },
        ),
        apiFactory = { _, _, _, _ -> api },
    )
}

private fun <T> onRepositoryThread(block: () -> T): T {
    var result: Result<T>? = null
    val thread = Thread { result = runCatching(block) }
    thread.start()
    thread.join()
    return requireNotNull(result).getOrThrow()
}

private enum class Failure {
    MissingFeature,
    ConfigFetch,
    ConfigSave,
    Register,
    InstallationId,
    Claim,
    StaleClaim,
    Profile,
    None,
}

private data class FailureCase(
    val failure: Failure,
    val expectedTrace: List<String>,
    val errorType: Class<out Throwable>,
)

private data class ActivationRequest(
    val deviceId: String,
    val installationId: String,
    val configId: String,
)

private class RepositoryFixture(
    failure: Failure = Failure.None,
    existingOptions: FirebaseOptions? = null,
) {
    val trace = mutableListOf<String>()
    val authStore = AuthStore(TraceAuthKeyValueStore(trace), PrefixTokenCipher())
    val configStore = FirebaseConfigStore(
        TraceFirebaseConfigPersistence(trace, acceptCommits = failure != Failure.ConfigSave),
    )
    val api = FakeApiClient(
        trace = trace,
        serverInfo = if (failure == Failure.MissingFeature) healthyInfo(features = emptySet()) else healthyInfo(),
        firebaseConfigError = ApiException(503, "config unavailable").takeIf { failure == Failure.ConfigFetch },
        claimError = when (failure) {
            Failure.Claim -> ApiException(503, "claim unavailable")
            Failure.StaleClaim -> ApiException(409, "stale Firebase config")
            else -> null
        },
        error = ApiException(503, "profile unavailable").takeIf { failure == Failure.Profile },
    )
    val repository = ContactRepository(
        authStore = authStore,
        firebaseConfigStore = configStore,
        firebaseBootstrap = FirebaseBootstrap(configStore, TraceFirebaseRuntime(trace, existingOptions)),
        firebaseRegistration = FirebaseRegistration(
            register = {
                trace += "register"
                if (failure == Failure.Register) {
                    Tasks.forException(IllegalStateException("registration failed"))
                } else {
                    Tasks.forResult(null)
                }
            },
            installationId = {
                trace += "installation-id"
                if (failure == Failure.InstallationId) {
                    Tasks.forException(IllegalStateException("FID failed"))
                } else {
                    Tasks.forResult("fid-123")
                }
            },
        ),
        onSessionActivated = { _, _, _, _ -> trace += "persist-registration" },
        apiFactory = { _, _, _, _ -> api },
    )
}

private class FakeApiClient(
    private val trace: MutableList<String> = mutableListOf(),
    private val serverInfo: ServerInfo = healthyInfo(),
    private val firebaseConfigError: RuntimeException? = null,
    private val claimError: RuntimeException? = null,
    private val profile: Profile = Profile("alice", "Alice"),
    private val contacts: List<Contact> = listOf(Contact("alice", "Alice"), Contact("bob", "Bob")),
    private val callHistory: CallHistoryPage = CallHistoryPage(emptyList(), 0, 0, 0),
    private val error: RuntimeException? = null,
    private val beforeError: (() -> Unit)? = null,
) : HouseholdApi {
    var profileRequests = 0
    var contactsRequests = 0
    var requestedPeer: String? = null
    var claim: ActivationRequest? = null

    override fun serverInfo(): ServerInfo {
        trace += "health"
        return serverInfo
    }

    override fun firebaseConfig(): FirebaseClientConfig {
        trace += "firebase-config"
        firebaseConfigError?.let { throw it }
        return clientConfig()
    }

    override fun me(): Profile {
        trace += "me"
        profileRequests++
        error?.let {
            beforeError?.invoke()
            throw it
        }
        return profile
    }

    override fun contactsPage(limit: Int, cursor: String): ContactPage {
        trace += "contacts"
        contactsRequests++
        return ContactPage(contacts, "")
    }

    override fun updateContactName(login: String, customName: String?): Contact = Contact(login, customName ?: login)
    override fun calls(limit: Int, before: Long, peerLogin: String?): CallHistoryPage {
        requestedPeer = peerLogin
        return callHistory
    }
    override fun markCallsRead(throughId: Long, peerLogin: String?): CallUnreadState = CallUnreadState(0, emptyList())
    override fun putDevice(deviceId: String, firebaseInstallationId: String, configId: String) = Unit
    override fun claimSession(deviceId: String, firebaseInstallationId: String, configId: String): String {
        trace += "claim-session"
        claim = ActivationRequest(deviceId, firebaseInstallationId, configId)
        claimError?.let { throw it }
        return "session-123"
    }
}

private class TraceAuthKeyValueStore(private val trace: MutableList<String>) : KeyValueStore {
    private val values = linkedMapOf<String, String>()
    override fun get(key: String): String? = values[key]
    override fun put(key: String, value: String) {
        if (key == "url") trace += "save-session"
        values[key] = value
    }
    override fun remove(vararg keys: String) {
        keys.forEach(values::remove)
    }
    override fun values(): List<String> = values.values.toList()
}

private class TraceFirebaseConfigPersistence(
    private val trace: MutableList<String>,
    private val acceptCommits: Boolean,
) : FirebaseConfigPersistence {
    private var value: String? = null
    override fun read(): String? = value
    override fun commit(value: String?): Boolean {
        trace += "save-config"
        this.value = value
        return acceptCommits
    }
}

private class TraceFirebaseRuntime(
    private val trace: MutableList<String>,
    private val existingOptions: FirebaseOptions?,
) : FirebaseRuntime {
    override fun currentOptions(): FirebaseOptions? {
        trace += "bootstrap"
        return existingOptions
    }
    override fun initialize(options: FirebaseOptions) = Unit
}

private object NoOpFirebaseRuntime : FirebaseRuntime {
    override fun currentOptions(): FirebaseOptions? = firebaseOptions()
    override fun initialize(options: FirebaseOptions) = Unit
}

private fun firebaseConfigStore(config: StoredFirebaseConfig?): FirebaseConfigStore {
    val persistence = object : FirebaseConfigPersistence {
        var value: String? = null
        override fun read(): String? = value
        override fun commit(value: String?): Boolean {
            this.value = value
            return true
        }
    }
    return FirebaseConfigStore(persistence).also { store ->
        config?.let {
            store.save(
                it.serverUrl,
                FirebaseClientConfig(it.applicationId, it.apiKey, it.projectId, it.gcmSenderId, it.configId),
            )
        }
    }
}

private fun healthyInfo(features: Set<String> = setOf("dynamic_fcm_v1", "single_device_session")) =
    ServerInfo("tinitalk", "ok", 3, features = features)

private fun clientConfig() = FirebaseClientConfig(
    applicationId = "1:123:android:abc",
    apiKey = "public-api-key",
    projectId = "demo-project",
    gcmSenderId = "123",
    configId = "sha256:config",
)

private fun storedConfig(serverUrl: String, configId: String) = StoredFirebaseConfig(
    serverUrl = serverUrl,
    applicationId = "1:123:android:abc",
    apiKey = "public-api-key",
    projectId = "demo-project",
    gcmSenderId = "123",
    configId = configId,
)

private fun firebaseOptions(projectId: String = "demo-project") = FirebaseOptions.Builder()
    .setApplicationId("1:123:android:abc")
    .setApiKey("public-api-key")
    .setProjectId(projectId)
    .setGcmSenderId("123")
    .build()
