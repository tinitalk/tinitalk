package org.tinitalk.push

import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.firebase.installations.FirebaseInstallations
import com.google.firebase.messaging.FirebaseMessaging
import java.util.concurrent.ExecutionException

class FirebaseRegistration internal constructor(
    private val register: () -> Task<Void>,
    private val installationId: () -> Task<String>,
) {
    constructor() : this(
        register = { FirebaseMessaging.getInstance().register() },
        installationId = { FirebaseInstallations.getInstance().id },
    )

    fun registerAndGetInstallationId(): String {
        await(register())
        return await(installationId()).takeIf(String::isNotBlank)
            ?: throw IllegalStateException("empty Firebase installation ID")
    }

    private fun <T> await(task: Task<T>): T = try {
        Tasks.await(task)
    } catch (error: ExecutionException) {
        throw error.cause ?: error
    }
}
