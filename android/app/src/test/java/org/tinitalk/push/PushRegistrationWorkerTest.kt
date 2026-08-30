package org.tinitalk.push

import org.tinitalk.data.ApiException
import org.tinitalk.data.AuthSessionEvent
import org.tinitalk.data.AuthSessionEvents
import org.tinitalk.data.AuthStore
import org.tinitalk.data.MemoryKeyValueStore
import org.tinitalk.data.PrefixTokenCipher
import org.tinitalk.data.Session
import org.tinitalk.data.SessionReplacedReason
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PushRegistrationWorkerTest {
    @Test
    fun newlyActivatedAccountRunsAfterSuccessfulStaleUploadAndOwnsTheFid() {
        val registrationStore = PushRegistrationStore(RecordingPushRegistrationPersistence())
        val authStore = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher())
        val config = storedConfig("https://server.example.test", "config-1")
        val alice = Session(
            config.serverUrl,
            "alice",
            "alice-token",
            sessionId = "alice-session",
            configId = config.configId,
        )
        val bob = Session(
            config.serverUrl,
            "bob",
            "bob-token",
            sessionId = "bob-session",
            configId = config.configId,
        )
        authStore.save(alice)
        registrationStore.upsert(
            config.serverUrl,
            config.configId,
            "device-1",
            "alice-session",
            "fid-shared",
        )
        var serverOwner = "alice"
        var enqueued = 0
        val staleAliceRunner = PushRegistrationRunner(
            registrationStore,
            loadConfig = { config },
            authStore = authStore,
            deviceId = { "device-1" },
            upload = { uploadedSession, _ ->
                serverOwner = "bob" // Bob's session claim committed first.
                authStore.save(bob)
                persistRegisteredInstallation(
                    installationId = "fid-shared",
                    config = config,
                    session = bob,
                    deviceId = "device-1",
                    store = registrationStore,
                    enqueue = { enqueued++ },
                )
                serverOwner = uploadedSession.login // Alice's delayed request completes last.
            },
        )

        assertEquals(PushRegistrationAttemptResult.SUCCESS, staleAliceRunner.runAttempt())
        assertEquals("alice", serverOwner)
        assertEquals("bob-session", registrationStore.load()?.sessionId)
        assertEquals(1, enqueued)

        val bobSuccessor = PushRegistrationRunner(
            registrationStore,
            loadConfig = { config },
            authStore = authStore,
            deviceId = { "device-1" },
            upload = { uploadedSession, _ -> serverOwner = uploadedSession.login },
        )
        assertEquals(PushRegistrationAttemptResult.SUCCESS, bobSuccessor.runAttempt())
        assertEquals("bob", serverOwner)
        assertNull(registrationStore.load())
    }

    @Test
    fun appliesRetryAndReloginPolicyWithoutDiscardingPendingRegistration() {
        data class Case(
            val name: String,
            val failure: Throwable?,
            val expected: PushRegistrationAttemptResult,
            val authCleared: Boolean = false,
            val invalidated: Boolean = false,
        )
        val cases = listOf(
            Case("204", null, PushRegistrationAttemptResult.SUCCESS),
            Case("offline", IOException("offline"), PushRegistrationAttemptResult.RETRY),
            Case("408", ApiException(408, "timeout"), PushRegistrationAttemptResult.RETRY),
            Case("429", ApiException(429, "busy"), PushRegistrationAttemptResult.RETRY),
            Case("5xx", ApiException(503, "down"), PushRegistrationAttemptResult.RETRY),
            Case("ordinary 401", ApiException(401, "unauthorized"), PushRegistrationAttemptResult.SUCCESS, authCleared = true),
            Case(
                "session replaced",
                ApiException(401, "unauthorized", SessionReplacedReason),
                PushRegistrationAttemptResult.SUCCESS,
                authCleared = true,
                invalidated = true,
            ),
            Case("permanent 409", ApiException(409, "conflict"), PushRegistrationAttemptResult.SUCCESS, authCleared = true),
            Case("unexpected 302", ApiException(302, "redirect"), PushRegistrationAttemptResult.SUCCESS),
        )

        cases.forEach { case ->
            val fixture = workerFixture(case.failure)
            val invalidations = mutableListOf<AuthSessionEvent>()
            val observer: (AuthSessionEvent) -> Unit = { invalidations += it }
            AuthSessionEvents.observe(observer)
            try {
                assertEquals(case.name, case.expected, fixture.runner.runAttempt())
                assertEquals(case.name, case.authCleared, fixture.authStore.load() == null)
                assertEquals(case.name, case.invalidated, invalidations.isNotEmpty())
                assertEquals(case.name, "current-token", fixture.uploadedSession?.token)
                if (case.failure == null) {
                    assertEquals(case.name, null, fixture.registrationStore.load())
                } else {
                    assertEquals(case.name, "fid-old", fixture.registrationStore.load()?.installationId)
                }
            } finally {
                AuthSessionEvents.removeObserver(observer)
                AuthSessionEvents.clear()
            }
        }
    }

    @Test
    fun staleOrReplacedStateIsATerminalNoOpAndCannotMutateNewerState() {
        val configConflict = workerFixture(null, currentConfigId = "config-new")
        assertEquals(PushRegistrationAttemptResult.SUCCESS, configConflict.runner.runAttempt())
        assertEquals(0, configConflict.uploadCount)
        assertEquals("fid-old", configConflict.registrationStore.load()?.installationId)

        val generationRace = workerFixture(null)
        generationRace.onUpload = {
            generationRace.registrationStore.upsert(
                "https://server.example.test",
                "config-1",
                "device-1",
                "session-1",
                "fid-new",
            )
        }
        assertEquals(PushRegistrationAttemptResult.SUCCESS, generationRace.runner.runAttempt())
        assertEquals("fid-new", generationRace.registrationStore.load()?.installationId)

        data class TerminalRace(val failure: ApiException, val replaceAuth: Boolean)
        listOf(
            TerminalRace(ApiException(401, "unauthorized"), replaceAuth = true),
            TerminalRace(ApiException(401, "unauthorized", SessionReplacedReason), replaceAuth = true),
            TerminalRace(ApiException(302, "redirect"), replaceAuth = false),
        ).forEach { race ->
            val terminalRace = workerFixture(race.failure)
            terminalRace.onUpload = {
                if (race.replaceAuth) {
                    terminalRace.authStore.save(
                        Session(
                            "https://server.example.test",
                            "bob",
                            "new-token",
                            sessionId = "session-new",
                            configId = "config-1",
                        ),
                    )
                }
                terminalRace.registrationStore.upsert(
                    "https://server.example.test",
                    "config-1",
                    "device-1",
                    if (race.replaceAuth) "session-new" else "session-1",
                    "fid-new",
                )
            }
            assertEquals(PushRegistrationAttemptResult.SUCCESS, terminalRace.runner.runAttempt())
            assertEquals("fid-new", terminalRace.registrationStore.load()?.installationId)
            assertEquals(
                if (race.replaceAuth) "session-new" else "session-1",
                terminalRace.authStore.load()?.sessionId,
            )
        }
    }

    @Test
    fun retriesWhenSuccessfulUploadCannotDurablyClearTheSameGeneration() {
        val fixture = workerFixture(null)
        fixture.onUpload = {
            if (fixture.uploadCount == 1) fixture.persistence.acceptCommits = false
        }

        assertEquals(PushRegistrationAttemptResult.RETRY, fixture.runner.runAttempt())
        assertEquals("fid-old", fixture.registrationStore.load()?.installationId)
        fixture.persistence.acceptCommits = true
        fixture.onUpload = {}
        assertEquals(PushRegistrationAttemptResult.SUCCESS, fixture.runner.runAttempt())
        assertEquals(2, fixture.uploadCount)
        assertEquals(null, fixture.registrationStore.load())
    }
}

private class WorkerFixture(failure: Throwable?, currentConfigId: String) {
    val persistence = RecordingPushRegistrationPersistence()
    val registrationStore = PushRegistrationStore(persistence)
    val authStore = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher())
    private val config = storedConfig("https://server.example.test", currentConfigId)
    var uploadedSession: Session? = null
    var uploadCount = 0
    var onUpload: () -> Unit = {}
    val runner: PushRegistrationRunner

    init {
        registrationStore.upsert(
            "https://server.example.test",
            "config-1",
            "device-1",
            "session-1",
            "fid-old",
        )
        authStore.save(session("https://server.example.test", "session-1", currentConfigId))
        runner = PushRegistrationRunner(
            registrationStore,
            loadConfig = { config },
            authStore = authStore,
            deviceId = { "device-1" },
            upload = { current, _ ->
                uploadCount++
                uploadedSession = current
                onUpload()
                failure?.let { throw it }
            },
        )
    }
}

private fun workerFixture(
    failure: Throwable?,
    currentConfigId: String = "config-1",
) = WorkerFixture(failure, currentConfigId)
