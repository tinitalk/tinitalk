package org.tinitalk.push

import org.tinitalk.data.AccountId
import org.tinitalk.data.ApiException
import org.tinitalk.data.AuthStore
import org.tinitalk.data.KeyValueStore
import org.tinitalk.data.MemoryKeyValueStore
import org.tinitalk.data.PrefixTokenCipher
import org.tinitalk.data.Session
import org.tinitalk.data.SessionReplacedReason
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PushRegistrationWorkerTest {
    @Test
    fun onlyConfirmedSessionReplacementRemovesTheAccount() {
        data class Case(
            val name: String,
            val error: Throwable,
            val expectedResult: String,
            val removed: Boolean,
        )
        val cases = listOf(
            Case("ordinary 401", ApiException(401, "unauthorized"), "SUCCESS", false),
            Case("bad request", ApiException(400, "bad request"), "SUCCESS", false),
            Case("stale config", ApiException(409, "stale config"), "REFRESH_CONFIG", false),
            Case("offline", IOException("offline"), "RETRY", false),
            Case("timeout", ApiException(408, "timeout"), "RETRY", false),
            Case("rate limited", ApiException(429, "rate limited"), "RETRY", false),
            Case("server failure", ApiException(503, "unavailable"), "RETRY", false),
            Case(
                "session replaced",
                ApiException(401, "unauthorized", SessionReplacedReason),
                "SUCCESS",
                true,
            ),
        )

        cases.forEach { case ->
            val fixture = registrationFixture(case.error)

            val result = fixture.runner.runAttempt()

            assertEquals(case.name, case.expectedResult, result.name)
            if (case.removed) {
                assertNull(case.name, fixture.authStore.get(fixture.accountId))
                assertEquals(case.name, listOf(fixture.accountId), fixture.removedAccounts)
            } else {
                assertNotNull(case.name, fixture.authStore.get(fixture.accountId))
                assertNotNull(case.name, fixture.registrationStore.load(fixture.accountId))
                assertEquals(case.name, emptyList<AccountId>(), fixture.removedAccounts)
            }
        }
    }

    @Test
    fun staleConfigIsFetchedStoredAndRegisteredWithANewSubscription() {
        val fixture = registrationFixture(ApiException(409, "stale config"))
        val refreshedSubscription = WebPushSubscription(
            "https://push.example/refreshed",
            WebPushKeys("p256dh-new", "auth-new"),
        )
        var fetchedFor: Session? = null
        var subscribedWith: StoredWebPushConfig? = null
        val refresher = StaleWebPushConfigRefresher(
            accountId = fixture.accountId,
            authStore = fixture.authStore,
            deviceId = "device-a",
            fetch = { session ->
                fetchedFor = session
                WebPushClientConfig("vapid-new", "config-new")
            },
            subscribe = { _, config ->
                subscribedWith = config
                refreshedSubscription
            },
            onStaleSubscription = { error("account must remain current") },
        )
        val original = requireNotNull(fixture.authStore.get(fixture.accountId)).session

        assertTrue(refresher.refresh(original))

        assertEquals(original, fetchedFor)
        assertEquals("config-new", fixture.authStore.get(fixture.accountId)?.session?.configId)
        assertEquals("config-new", fixture.authStore.webPushConfig(fixture.accountId)?.configId)
        assertEquals("config-new", subscribedWith?.configId)
        val pending = requireNotNull(fixture.registrationStore.load(fixture.accountId))
        assertEquals("config-new", pending.configId)
        assertEquals(refreshedSubscription, pending.subscription)
    }

    @Test
    fun successfulConfigRefreshIsFollowedByOneUploadRetry() {
        val attempts = ArrayDeque(
            listOf(
                PushRegistrationAttemptResult.REFRESH_CONFIG,
                PushRegistrationAttemptResult.SUCCESS,
            ),
        )
        var refreshes = 0

        val result = runPushRegistrationAttempt(
            attempt = { attempts.removeFirst() },
            refreshConfig = { refreshes++; true },
        )

        assertEquals(PushRegistrationAttemptResult.SUCCESS, result)
        assertEquals(1, refreshes)
        assertTrue(attempts.isEmpty())
    }

    @Test
    fun configRefreshRetriesOnlyTemporaryFailures() {
        data class Case(val error: Throwable, val expected: PushRegistrationAttemptResult)
        val cases = listOf(
            Case(ApiException(400, "bad request"), PushRegistrationAttemptResult.SUCCESS),
            Case(ApiException(401, "unauthorized"), PushRegistrationAttemptResult.SUCCESS),
            Case(ApiException(403, "forbidden"), PushRegistrationAttemptResult.SUCCESS),
            Case(ApiException(404, "not found"), PushRegistrationAttemptResult.SUCCESS),
            Case(IOException("offline"), PushRegistrationAttemptResult.RETRY),
            Case(ApiException(408, "timeout"), PushRegistrationAttemptResult.RETRY),
            Case(ApiException(429, "rate limited"), PushRegistrationAttemptResult.RETRY),
            Case(ApiException(503, "unavailable"), PushRegistrationAttemptResult.RETRY),
        )

        cases.forEach { case ->
            val result = runPushRegistrationAttempt(
                attempt = { PushRegistrationAttemptResult.REFRESH_CONFIG },
                refreshConfig = { throw case.error },
            )

            assertEquals(case.error.toString(), case.expected, result)
        }
    }

    @Test
    fun staleAccountIsRejectedBeforeExternalSubscribe() {
        val fixture = registrationFixture(ApiException(409, "stale config"))
        val original = requireNotNull(fixture.authStore.get(fixture.accountId)).session
        val currentConfig = StoredWebPushConfig("https://a.example", "vapid-current", "config-current")
        val current = original.copy(token = "token-current", sessionId = "session-current", configId = currentConfig.configId)
        assertTrue(fixture.authStore.removeIfCurrent(fixture.accountId, original))
        fixture.authStore.add(fixture.accountId, current, currentConfig)
        var subscriptions = 0
        val refresher = StaleWebPushConfigRefresher(
            accountId = fixture.accountId,
            authStore = fixture.authStore,
            deviceId = "device-a",
            fetch = { WebPushClientConfig("vapid-new", "config-new") },
            subscribe = { _, _ ->
                subscriptions++
                WebPushSubscription("https://push.example/stale", WebPushKeys("p256dh", "auth"))
            },
            onStaleSubscription = { error("external state must not change") },
        )

        assertFalse(refresher.refresh(original))
        assertEquals(0, subscriptions)
        assertEquals(current, fixture.authStore.get(fixture.accountId)?.session)
    }

    @Test
    fun sessionMutationDuringSubscribeRestoresTheCurrentExternalConfig() {
        val fixture = registrationFixture(ApiException(409, "stale config"))
        val original = requireNotNull(fixture.authStore.get(fixture.accountId)).session
        val currentConfig = StoredWebPushConfig("https://a.example", "vapid-current", "config-current")
        val current = original.copy(token = "token-current", sessionId = "session-current", configId = currentConfig.configId)
        val registration = RecordingAccountWebPushRegistration()
        val refresher = StaleWebPushConfigRefresher(
            accountId = fixture.accountId,
            authStore = fixture.authStore,
            deviceId = "device-a",
            fetch = { WebPushClientConfig("vapid-new", "config-new") },
            subscribe = { _, _ ->
                assertTrue(fixture.authStore.removeIfCurrent(fixture.accountId, original))
                fixture.authStore.add(fixture.accountId, current, currentConfig)
                WebPushSubscription("https://push.example/stale", WebPushKeys("p256dh", "auth"))
            },
            onStaleSubscription = { accountId ->
                recoverCurrentWebPushRegistration(accountId, fixture.authStore, registration)
            },
        )

        assertFalse(refresher.refresh(original))

        assertEquals(current, fixture.authStore.get(fixture.accountId)?.session)
        assertEquals(listOf(fixture.accountId to currentConfig), registration.restored)
        assertTrue(registration.unsubscribed.isEmpty())
    }

    @Test
    fun transientRestoreFailureDoesNotUnsubscribeAValidAccount() {
        val fixture = registrationFixture(ApiException(409, "stale config"))
        val failure = IOException("temporary UnifiedPush failure")
        val registration = RecordingAccountWebPushRegistration(restoreFailure = failure)

        val actual = runCatching {
            recoverCurrentWebPushRegistration(fixture.accountId, fixture.authStore, registration)
        }.exceptionOrNull()

        assertEquals(failure, actual)
        assertTrue(registration.unsubscribed.isEmpty())
    }

    @Test
    fun missingAccountRecoveryDoesNotUnsubscribeANewerRegistration() {
        val fixture = registrationFixture(ApiException(409, "stale config"))
        val current = requireNotNull(fixture.authStore.get(fixture.accountId)).session
        assertTrue(fixture.authStore.removeIfCurrent(fixture.accountId, current))
        val registration = RecordingAccountWebPushRegistration()

        recoverCurrentWebPushRegistration(fixture.accountId, fixture.authStore, registration)

        assertTrue(registration.unsubscribed.isEmpty())
    }

    @Test
    fun refreshedConfigAndPendingRegistrationUseOnePersistentWrite() {
        val persistence = CountingKeyValueStore()
        val fixture = RegistrationFixture(ApiException(409, "stale config"), persistence)
        persistence.resetPutCount()
        val refresher = StaleWebPushConfigRefresher(
            accountId = fixture.accountId,
            authStore = fixture.authStore,
            deviceId = "device-a",
            fetch = { WebPushClientConfig("vapid-new", "config-new") },
            subscribe = { _, _ ->
                WebPushSubscription("https://push.example/new", WebPushKeys("p256dh-new", "auth-new"))
            },
            onStaleSubscription = { error("account must remain current") },
        )
        val original = requireNotNull(fixture.authStore.get(fixture.accountId)).session

        assertTrue(refresher.refresh(original))

        assertEquals(1, persistence.putCount)
        assertEquals("config-new", fixture.authStore.webPushConfig(fixture.accountId)?.configId)
        assertEquals("config-new", fixture.registrationStore.load(fixture.accountId)?.configId)
    }

}

private class RegistrationFixture(
    error: Throwable,
    private val persistence: KeyValueStore = MemoryKeyValueStore(),
) {
    val accountId = AccountId("account-a")
    val authStore = AuthStore(persistence, PrefixTokenCipher())
    val registrationStore = PushRegistrationStore(persistence)
    val removedAccounts = mutableListOf<AccountId>()
    val runner: PushRegistrationRunner

    init {
        val config = StoredWebPushConfig("https://a.example", "vapid-a", "config-a")
        val session = Session(
            url = config.serverUrl,
            login = "alice",
            token = "token-a",
            sessionId = "session-a",
            configId = config.configId,
        )
        authStore.add(accountId, session, config)
        registrationStore.upsert(
            accountId,
            session,
            "device-a",
            WebPushSubscription("https://push.example/a", WebPushKeys("p256dh-a", "auth-a")),
        )
        runner = PushRegistrationRunner(
            accountId = accountId,
            registrationStore = registrationStore,
            authStore = authStore,
            deviceId = { "device-a" },
            onAccountRemoved = removedAccounts::add,
            upload = { _, _ -> throw error },
        )
    }
}

private class CountingKeyValueStore(
    private val delegate: MemoryKeyValueStore = MemoryKeyValueStore(),
) : KeyValueStore {
    var putCount: Int = 0
        private set

    fun resetPutCount() {
        putCount = 0
    }

    override fun get(key: String): String? = delegate.get(key)

    override fun put(key: String, value: String) {
        putCount++
        delegate.put(key, value)
    }

    override fun remove(vararg keys: String) = delegate.remove(*keys)

    override fun values(): List<String> = delegate.values()
}

private fun registrationFixture(error: Throwable) = RegistrationFixture(error)

private class RecordingAccountWebPushRegistration(
    private val restoreFailure: Throwable? = null,
) : AccountWebPushRegistration {
    val restored = mutableListOf<Pair<AccountId, StoredWebPushConfig>>()
    val unsubscribed = mutableListOf<AccountId>()

    override fun subscribe(accountId: AccountId, config: StoredWebPushConfig): WebPushSubscription =
        error("subscribe is not expected")

    override fun restore(accountId: AccountId, config: StoredWebPushConfig) {
        restoreFailure?.let { throw it }
        restored += accountId to config
    }

    override fun unsubscribe(accountId: AccountId) {
        unsubscribed += accountId
    }
}
