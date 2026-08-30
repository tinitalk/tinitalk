package org.tinitalk.push

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions

sealed interface FirebaseBootstrapResult {
    data object Absent : FirebaseBootstrapResult
    data object Initialized : FirebaseBootstrapResult
    data object AlreadyInitialized : FirebaseBootstrapResult
    data object ConfigurationMismatch : FirebaseBootstrapResult
}

internal interface FirebaseRuntime {
    fun currentOptions(): FirebaseOptions?
    fun initialize(options: FirebaseOptions)
}

class FirebaseBootstrap internal constructor(
    private val store: FirebaseConfigStore,
    private val runtime: FirebaseRuntime,
) {
    constructor(context: Context) : this(
        FirebaseConfigStore(context),
        DefaultFirebaseRuntime(context),
    )

    fun restore(): FirebaseBootstrapResult {
        val stored = store.load() ?: return FirebaseBootstrapResult.Absent
        val requested = stored.toFirebaseOptions()
        val existing = runtime.currentOptions()
        if (existing == null) {
            runtime.initialize(requested)
            return FirebaseBootstrapResult.Initialized
        }
        return if (existing.sameClientConfiguration(requested)) {
            FirebaseBootstrapResult.AlreadyInitialized
        } else {
            FirebaseBootstrapResult.ConfigurationMismatch
        }
    }

    private fun StoredFirebaseConfig.toFirebaseOptions(): FirebaseOptions = FirebaseOptions.Builder()
        .setApplicationId(applicationId)
        .setApiKey(apiKey)
        .setProjectId(projectId)
        .setGcmSenderId(gcmSenderId)
        .build()

    private fun FirebaseOptions.sameClientConfiguration(other: FirebaseOptions): Boolean =
        applicationId == other.applicationId &&
            apiKey == other.apiKey &&
            projectId == other.projectId &&
            gcmSenderId == other.gcmSenderId
}

private class DefaultFirebaseRuntime(
    private val context: Context,
) : FirebaseRuntime {
    override fun currentOptions(): FirebaseOptions? = try {
        FirebaseApp.getInstance().options
    } catch (_: IllegalStateException) {
        null
    }

    override fun initialize(options: FirebaseOptions) {
        FirebaseApp.initializeApp(context, options)
    }
}
