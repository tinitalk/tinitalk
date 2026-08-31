package org.tinitalk.push

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.tinitalk.cleanupWebPushAccount
import org.tinitalk.data.AccountId
import org.tinitalk.data.AccountStorageException
import org.tinitalk.data.AndroidKeystoreTokenCipher
import org.tinitalk.data.ApiException
import org.tinitalk.data.AuthRemovalReason
import org.tinitalk.data.AuthStore
import org.tinitalk.data.Session
import org.tinitalk.data.SessionReplacedReason
import org.tinitalk.data.SharedPreferencesKeyValueStore
import org.tinitalk.data.UrlConnectionApiClient
import org.tinitalk.data.sameIdentity
import java.io.IOException

internal enum class PushRegistrationAttemptResult {
    SUCCESS,
    RETRY,
}

internal class PushRegistrationRunner(
    private val accountId: AccountId,
    private val registrationStore: PushRegistrationStore,
    private val authStore: AuthStore,
    private val deviceId: () -> String,
    private val onAccountRemoved: (AccountId) -> Unit,
    private val upload: (Session, PendingPushRegistration) -> Unit,
) {
    fun runAttempt(): PushRegistrationAttemptResult {
        val account = try {
            authStore.get(accountId) ?: return PushRegistrationAttemptResult.SUCCESS
        } catch (_: AccountStorageException) {
            return PushRegistrationAttemptResult.SUCCESS
        } catch (_: Exception) {
            return PushRegistrationAttemptResult.RETRY
        }
        val config = authStore.webPushConfig(accountId) ?: return PushRegistrationAttemptResult.SUCCESS
        val pending = try {
            registrationStore.loadBoundTo(accountId, config, account.session, deviceId())
                ?: return PushRegistrationAttemptResult.SUCCESS
        } catch (_: AccountStorageException) {
            return PushRegistrationAttemptResult.SUCCESS
        } catch (_: Exception) {
            return PushRegistrationAttemptResult.RETRY
        }
        try {
            val current = authStore.get(accountId) ?: return PushRegistrationAttemptResult.SUCCESS
            if (!current.session.sameIdentity(account.session) || registrationStore.load(accountId) != pending) {
                return PushRegistrationAttemptResult.SUCCESS
            }
            upload(account.session, pending)
        } catch (_: IOException) {
            return PushRegistrationAttemptResult.RETRY
        } catch (error: ApiException) {
            return handleApiFailure(error, account.session)
        } catch (_: AccountStorageException) {
            return PushRegistrationAttemptResult.RETRY
        }
        return when (registrationStore.clearIfGeneration(accountId, pending.generation)) {
            PushRegistrationClearResult.CLEARED,
            PushRegistrationClearResult.STALE,
            -> PushRegistrationAttemptResult.SUCCESS
            PushRegistrationClearResult.FAILED -> PushRegistrationAttemptResult.RETRY
        }
    }

    private fun handleApiFailure(error: ApiException, session: Session): PushRegistrationAttemptResult = try {
        when {
            error.code == 408 || error.code == 429 || error.code in 500..599 ->
                PushRegistrationAttemptResult.RETRY
            error.code == 401 && error.authReason == SessionReplacedReason -> {
                removeAccount(session, AuthRemovalReason.SessionReplaced)
                PushRegistrationAttemptResult.SUCCESS
            }
            error.code in 400..499 -> {
                removeAccount(session, AuthRemovalReason.TerminalFailure)
                PushRegistrationAttemptResult.SUCCESS
            }
            else -> PushRegistrationAttemptResult.SUCCESS
        }
    } catch (_: AccountStorageException) {
        PushRegistrationAttemptResult.RETRY
    }

    private fun removeAccount(session: Session, reason: AuthRemovalReason) {
        if (authStore.invalidateIfCurrent(accountId, session, reason)) {
            runCatching { onAccountRemoved(accountId) }
        }
    }
}

class PushRegistrationWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : Worker(appContext, workerParameters) {
    override fun doWork(): Result {
        val accountId = inputData.getString(AccountIdInputKey)
            ?.let { value -> runCatching { AccountId(value) }.getOrNull() }
            ?: return Result.success()
        val authStore = AuthStore(
            SharedPreferencesKeyValueStore(applicationContext),
            AndroidKeystoreTokenCipher(),
        )
        val config = authStore.webPushConfig(accountId) ?: return Result.success()
        if (runCatching {
                UnifiedPushAccountRegistration(applicationContext).restore(accountId, config)
            }.isFailure
        ) {
            return Result.retry()
        }

        val runner = PushRegistrationRunner(
            accountId = accountId,
            registrationStore = PushRegistrationStore(applicationContext),
            authStore = authStore,
            deviceId = { DeviceIdentity.id(applicationContext) },
            onAccountRemoved = { id -> cleanupWebPushAccount(applicationContext, id) },
            upload = { session, pending ->
                UrlConnectionApiClient(
                    session.url,
                    session.login,
                    session.token,
                    session.sessionId,
                ).putDevice(pending.deviceId, pending.subscription, pending.configId)
            },
        )
        return when (runner.runAttempt()) {
            PushRegistrationAttemptResult.SUCCESS -> Result.success()
            PushRegistrationAttemptResult.RETRY -> Result.retry()
        }
    }

    companion object {
        const val AccountIdInputKey = "account_id"
    }
}
