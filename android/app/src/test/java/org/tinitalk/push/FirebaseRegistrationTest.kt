package org.tinitalk.push

import com.google.android.gms.tasks.Tasks
import org.tinitalk.data.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FirebaseRegistrationTest {
    @Test
    fun registeredCallbackPersistsBeforeEnqueueAndReupsertsAnUnchangedFid() {
        val trace = mutableListOf<String>()
        val persistence = RecordingPushRegistrationPersistence().apply {
            onCommit = { trace += "persist" }
        }
        val store = PushRegistrationStore(persistence)
        val config = storedConfig("https://server.example.test", "config-1")
        val current = Session(
            "https://server.example.test",
            "alice",
            "token",
            sessionId = "session-1",
            configId = "config-1",
        )

        repeat(2) {
            persistRegisteredInstallation(
                installationId = "fid-123",
                config = config,
                session = current,
                deviceId = "device-1",
                store = store,
                enqueue = { trace += "enqueue" },
            )
        }

        assertEquals(listOf("persist", "enqueue", "persist", "enqueue"), trace)
        assertEquals(2L, store.load()?.generation)
        assertEquals("fid-123", store.load()?.installationId)
    }

    @Test
    fun waitsForRegistrationBeforeReadingInstallationId() {
        val trace = mutableListOf<String>()
        val registration = FirebaseRegistration(
            register = {
                trace += "register"
                Tasks.forResult(null)
            },
            installationId = {
                trace += "installation-id"
                Tasks.forResult("fid-123")
            },
        )

        assertEquals("fid-123", onBackgroundThread { registration.registerAndGetInstallationId() })
        assertEquals(listOf("register", "installation-id"), trace)
    }

    @Test
    fun registrationFailureDoesNotReadInstallationId() {
        val trace = mutableListOf<String>()
        val registration = FirebaseRegistration(
            register = {
                trace += "register"
                Tasks.forException(IllegalStateException("registration failed"))
            },
            installationId = {
                trace += "installation-id"
                Tasks.forResult("fid-123")
            },
        )

        assertThrows(IllegalStateException::class.java) {
            onBackgroundThread { registration.registerAndGetInstallationId() }
        }
        assertEquals(listOf("register"), trace)
    }

    @Test
    fun installationIdFailureIsPropagatedAfterRegistration() {
        val trace = mutableListOf<String>()
        val registration = FirebaseRegistration(
            register = {
                trace += "register"
                Tasks.forResult(null)
            },
            installationId = {
                trace += "installation-id"
                Tasks.forException(IllegalStateException("FID failed"))
            },
        )

        assertThrows(IllegalStateException::class.java) {
            onBackgroundThread { registration.registerAndGetInstallationId() }
        }
        assertEquals(listOf("register", "installation-id"), trace)
    }
}

private fun <T> onBackgroundThread(block: () -> T): T {
    var result: Result<T>? = null
    val thread = Thread { result = runCatching(block) }
    thread.start()
    thread.join()
    return requireNotNull(result).getOrThrow()
}
