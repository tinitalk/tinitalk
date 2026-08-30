package org.tinitalk.push

import com.google.firebase.FirebaseOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FirebaseBootstrapTest {
    @Test
    fun leavesFirebaseUninitializedWhenTheStoredConfigurationIsAbsent() {
        val runtime = RecordingFirebaseRuntime()

        val result = FirebaseBootstrap(
            FirebaseConfigStore(RecordingFirebaseConfigPersistence()),
            runtime,
        ).restore()

        assertEquals(FirebaseBootstrapResult.Absent, result)
        assertNull(runtime.initializedOptions)
    }

    @Test
    fun initializesTheDefaultAppWithTheStoredFirebaseOptions() {
        val runtime = RecordingFirebaseRuntime()
        val store = storedConfigStore()

        val result = FirebaseBootstrap(store, runtime).restore()

        assertEquals(FirebaseBootstrapResult.Initialized, result)
        assertEquals("1:123:android:abc", runtime.initializedOptions?.applicationId)
        assertEquals("public-api-key", runtime.initializedOptions?.apiKey)
        assertEquals("demo-project", runtime.initializedOptions?.projectId)
        assertEquals("123", runtime.initializedOptions?.gcmSenderId)
    }

    @Test
    fun treatsAnExistingDefaultAppWithTheSameOptionsAsInitialized() {
        val existing = options()
        val runtime = RecordingFirebaseRuntime(existing)
        val store = storedConfigStore(serverUrl = "https://other.example.test", configId = "other-metadata")

        val result = FirebaseBootstrap(store, runtime).restore()

        assertEquals(FirebaseBootstrapResult.AlreadyInitialized, result)
        assertNull(runtime.initializedOptions)
    }

    @Test
    fun returnsAMismatchWithoutReplacingAnExistingDefaultApp() {
        val existing = options(projectId = "different-project")
        val runtime = RecordingFirebaseRuntime(existing)

        val result = FirebaseBootstrap(storedConfigStore(), runtime).restore()

        assertEquals(FirebaseBootstrapResult.ConfigurationMismatch, result)
        assertNull(runtime.initializedOptions)
    }

    private fun storedConfigStore(
        serverUrl: String = "https://server.example.test",
        configId: String = "sha256:config",
    ): FirebaseConfigStore = FirebaseConfigStore(RecordingFirebaseConfigPersistence()).also { store ->
        store.save(
            serverUrl,
            FirebaseClientConfig(
                applicationId = "1:123:android:abc",
                apiKey = "public-api-key",
                projectId = "demo-project",
                gcmSenderId = "123",
                configId = configId,
            ),
        )
    }

    private fun options(projectId: String = "demo-project") = FirebaseOptions.Builder()
        .setApplicationId("1:123:android:abc")
        .setApiKey("public-api-key")
        .setProjectId(projectId)
        .setGcmSenderId("123")
        .build()
}

private class RecordingFirebaseRuntime(
    private val existingOptions: FirebaseOptions? = null,
) : FirebaseRuntime {
    var initializedOptions: FirebaseOptions? = null

    override fun currentOptions(): FirebaseOptions? = existingOptions

    override fun initialize(options: FirebaseOptions) {
        initializedOptions = options
    }
}
