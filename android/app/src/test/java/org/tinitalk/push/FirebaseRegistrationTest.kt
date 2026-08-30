package org.tinitalk.push

import com.google.android.gms.tasks.Tasks
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
