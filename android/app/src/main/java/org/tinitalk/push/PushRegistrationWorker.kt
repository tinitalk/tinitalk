package org.tinitalk.push

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.tinitalk.data.AndroidKeystoreTokenCipher
import org.tinitalk.data.ApiException
import org.tinitalk.data.AuthStore
import org.tinitalk.data.Session
import org.tinitalk.data.SessionReplacedReason
import org.tinitalk.data.SharedPreferencesKeyValueStore
import org.tinitalk.data.UrlConnectionApiClient
import java.io.IOException

internal enum class PushRegistrationAttemptResult {
    SUCCESS,
    RETRY,
}

internal class PushRegistrationRunner internal constructor(
    private val registrationStore: PushRegistrationStore,
    private val loadConfig: () -> StoredFirebaseConfig?,
    private val authStore: AuthStore,
    private val deviceId: () -> String,
    private val upload: (Session, PendingPushRegistration) -> Unit,
) {
    fun runAttempt(): PushRegistrationAttemptResult {
        val config = loadConfig() ?: return PushRegistrationAttemptResult.SUCCESS
        val session = authStore.loadBoundTo(config) ?: return PushRegistrationAttemptResult.SUCCESS
        val pending = registrationStore.loadBoundTo(config, session, deviceId())
            ?: return PushRegistrationAttemptResult.SUCCESS
        try {
            upload(session, pending)
        } catch (_: IOException) {
            return PushRegistrationAttemptResult.RETRY
        } catch (error: ApiException) {
            return handleApiFailure(error, session)
        }
        return when (registrationStore.clearIfGeneration(pending.generation)) {
            PushRegistrationClearResult.CLEARED,
            PushRegistrationClearResult.STALE,
            -> PushRegistrationAttemptResult.SUCCESS
            PushRegistrationClearResult.FAILED -> PushRegistrationAttemptResult.RETRY
        }
    }

    private fun handleApiFailure(error: ApiException, session: Session): PushRegistrationAttemptResult = when {
        error.code == 408 || error.code == 429 || error.code in 500..599 ->
            PushRegistrationAttemptResult.RETRY
        error.code == 401 && error.authReason == SessionReplacedReason -> {
            authStore.invalidateIfCurrent(session)
            PushRegistrationAttemptResult.SUCCESS
        }
        error.code in 400..499 -> {
            authStore.clearIfCurrent(session)
            PushRegistrationAttemptResult.SUCCESS
        }
        else -> PushRegistrationAttemptResult.SUCCESS
    }
}

class PushRegistrationWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : Worker(appContext, workerParameters) {
    override fun doWork(): Result {
        val authStore = AuthStore(
            SharedPreferencesKeyValueStore(applicationContext),
            AndroidKeystoreTokenCipher(),
        )
        val runner = PushRegistrationRunner(
            PushRegistrationStore(applicationContext),
            loadConfig = { FirebaseConfigStore(applicationContext).load() },
            authStore = authStore,
            deviceId = { DeviceIdentity.id(applicationContext) },
            upload = { session, pending ->
                UrlConnectionApiClient(
                    session.url,
                    session.login,
                    session.token,
                    session.sessionId,
                ).putDevice(pending.deviceId, pending.installationId, pending.configId)
            },
        )
        return when (runner.runAttempt()) {
            PushRegistrationAttemptResult.SUCCESS -> Result.success()
            PushRegistrationAttemptResult.RETRY -> Result.retry()
        }
    }
}
