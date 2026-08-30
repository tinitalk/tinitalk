package org.tinitalk.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class FirebaseConfigStoreTest {
    @Test
    fun roundTripsAndReplacesTheSingleNormalizedServerBinding() {
        val persistence = RecordingFirebaseConfigPersistence()
        val store = FirebaseConfigStore(persistence)
        store.save(" https://old.example.test/// ", config(configId = "old"))

        val replacement = store.save(" https://new.example.test/// ", config(configId = "new"))

        assertEquals(
            StoredFirebaseConfig(
                serverUrl = "https://new.example.test",
                applicationId = "1:123:android:abc",
                apiKey = "public-api-key",
                projectId = "demo-project",
                gcmSenderId = "123",
                configId = "new",
            ),
            replacement,
        )
        assertEquals(replacement, store.load())
        assertEquals(2, persistence.commitCount)
    }

    @Test
    fun rejectsMissingOrMalformedStoredDataWithoutCrashing() {
        val persistence = RecordingFirebaseConfigPersistence()
        val store = FirebaseConfigStore(persistence)

        assertNull(store.load())
        listOf("{not-json", "{}").forEach { malformed ->
            persistence.value = malformed
            assertNull(store.load())
        }
    }

    @Test
    fun surfacesSynchronousCommitFailureWithoutReplacingTheBinding() {
        val persistence = RecordingFirebaseConfigPersistence()
        val store = FirebaseConfigStore(persistence)
        val original = store.save("https://old.example.test", config(configId = "old"))
        persistence.acceptCommits = false

        assertThrows(FirebaseConfigCommitException::class.java) {
            store.save("https://new.example.test", config(configId = "new"))
        }
        assertEquals(original, store.load())
    }

    private fun config(configId: String) = FirebaseClientConfig(
        applicationId = "1:123:android:abc",
        apiKey = "public-api-key",
        projectId = "demo-project",
        gcmSenderId = "123",
        configId = configId,
    )
}

internal class RecordingFirebaseConfigPersistence(
    var value: String? = null,
) : FirebaseConfigPersistence {
    var acceptCommits = true
    var commitCount = 0

    override fun read(): String? = value

    override fun commit(value: String): Boolean {
        commitCount++
        if (acceptCommits) this.value = value
        return acceptCommits
    }
}
