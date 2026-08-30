package org.tinitalk.push

import org.tinitalk.data.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PushRegistrationStoreTest {
    @Test
    fun survivesRestartComparesGenerationAndNeverReusesClearedGeneration() {
        val persistence = RecordingPushRegistrationPersistence()
        val firstStore = PushRegistrationStore(persistence)
        val first = firstStore.upsert(
            "https://server.example.test/",
            "config-1",
            "device-1",
            "session-1",
            "fid-1",
        )
        val restored = PushRegistrationStore(persistence)

        assertEquals(first, restored.load())
        val second = restored.upsert(
            "https://server.example.test",
            "config-1",
            "device-1",
            "session-1",
            "fid-2",
        )
        assertEquals(1L, first.generation)
        assertEquals(2L, second.generation)
        assertEquals(PushRegistrationClearResult.STALE, restored.clearIfGeneration(first.generation))
        assertEquals(second, restored.load())
        assertEquals(PushRegistrationClearResult.CLEARED, restored.clearIfGeneration(second.generation))
        assertNull(restored.load())

        val third = PushRegistrationStore(persistence).upsert(
            "https://server.example.test",
            "config-1",
            "device-1",
            "session-1",
            "fid-3",
        )
        assertEquals(3L, third.generation)
    }

    @Test
    fun exposesCommitFailureWithoutLosingThePendingRow() {
        val persistence = RecordingPushRegistrationPersistence()
        val store = PushRegistrationStore(persistence)
        val pending = store.upsert(
            "https://server.example.test",
            "config-1",
            "device-1",
            "session-1",
            "fid-1",
        )
        val encodedPending = persistence.value
        persistence.acceptCommits = false

        assertEquals(PushRegistrationClearResult.FAILED, store.clearIfGeneration(pending.generation))
        assertEquals(encodedPending, persistence.value)
        assertEquals(pending, store.load())
        assertThrows(PushRegistrationCommitException::class.java) {
            store.upsert(
                "https://server.example.test",
                "config-1",
                "device-1",
                "session-1",
                "fid-2",
            )
        }
        assertEquals(encodedPending, persistence.value)
        assertEquals(pending, store.load())
    }

    @Test
    fun isolatesPendingRowsFromStaleConfigSessionAndDeviceBindings() {
        val store = PushRegistrationStore(RecordingPushRegistrationPersistence())
        val pending = store.upsert(
            "https://server.example.test",
            "config-1",
            "device-1",
            "session-1",
            "fid-1",
        )
        val exactConfig = storedConfig("https://server.example.test/", "config-1")
        val exactSession = session("https://server.example.test/", "session-1", "config-1")

        assertEquals(pending, store.loadBoundTo(exactConfig, exactSession, "device-1"))
        listOf(
            Triple(storedConfig("https://other.example.test", "config-1"), exactSession, "device-1"),
            Triple(storedConfig("https://server.example.test", "config-2"), exactSession, "device-1"),
            Triple(exactConfig, session("https://server.example.test", "session-2", "config-1"), "device-1"),
            Triple(exactConfig, session("https://server.example.test", "session-1", "config-2"), "device-1"),
            Triple(exactConfig, exactSession, "device-2"),
        ).forEach { (config, currentSession, deviceId) ->
            assertNull(store.loadBoundTo(config, currentSession, deviceId))
        }
    }
}

internal class RecordingPushRegistrationPersistence(
    var value: String? = null,
) : PushRegistrationPersistence {
    var acceptCommits = true
    var commitCount = 0
    var onCommit: (() -> Unit)? = null

    override fun read(): String? = value

    override fun commit(value: String?): Boolean {
        commitCount++
        onCommit?.invoke()
        this.value = value
        return acceptCommits
    }
}

internal fun storedConfig(serverUrl: String, configId: String) = StoredFirebaseConfig(
    serverUrl = serverUrl,
    applicationId = "1:123:android:abc",
    apiKey = "public-api-key",
    projectId = "demo-project",
    gcmSenderId = "123",
    configId = configId,
)

internal fun session(serverUrl: String, sessionId: String, configId: String) = Session(
    url = serverUrl,
    login = "alice",
    token = "current-token",
    sessionId = sessionId,
    configId = configId,
)
