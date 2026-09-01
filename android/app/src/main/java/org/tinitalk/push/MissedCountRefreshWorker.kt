package org.tinitalk.push

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.tinitalk.call.CallSessionBinding
import org.tinitalk.data.AccountId
import org.tinitalk.data.AccountStorageException
import org.tinitalk.data.AccountUnreadState
import org.tinitalk.data.AndroidKeystoreTokenCipher
import org.tinitalk.data.ApiException
import org.tinitalk.data.AuthStore
import org.tinitalk.data.CallHistoryEvents
import org.tinitalk.data.ContactRepository
import org.tinitalk.data.SharedPreferencesKeyValueStore
import java.io.IOException

internal class MissedCountRefreshScheduler internal constructor(
    private val enqueueWork: (String, ExistingWorkPolicy, OneTimeWorkRequest) -> Unit,
) {
    constructor(context: Context) : this(
        { name, policy, request ->
            WorkManager.getInstance(context).enqueueUniqueWork(name, policy, request)
        },
    )

    fun enqueue(accountId: AccountId) {
        val request = OneTimeWorkRequestBuilder<MissedCountRefreshWorker>()
            .setInputData(
                Data.Builder()
                    .putString(MissedCountRefreshWorker.AccountIdInputKey, accountId.value)
                    .build(),
            )
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        runCatching {
            enqueueWork(
                "missed-count-refresh:${accountId.value}",
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}

internal class MissedCountRefreshWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : Worker(appContext, workerParameters) {
    override fun doWork(): Result {
        val accountId = inputData.getString(AccountIdInputKey)
            ?.let { runCatching { AccountId(it) }.getOrNull() }
            ?: return Result.success()
        val authStore = AuthStore(
            SharedPreferencesKeyValueStore(applicationContext),
            AndroidKeystoreTokenCipher(),
        )
        val pinned = try {
            authStore.get(accountId)?.session ?: return Result.success()
        } catch (_: AccountStorageException) {
            return Result.success()
        } catch (_: Exception) {
            return Result.retry()
        }
        val notifier = IncomingCallNotifier(applicationContext)
        val refreshId = try {
            notifier.syncMissedAccounts(authStore.list().map { it.id })
            notifier.beginAccountMissedCountRefresh(accountId)
        } catch (_: AccountStorageException) {
            return Result.success()
        }
        val page = try {
            ContactRepository(authStore).loadCallHistory(accountId, limit = 1, expectedSession = pinned)
                ?: return Result.success()
        } catch (error: Exception) {
            return if (isTemporaryMissedCountRefreshFailure(error)) Result.retry() else Result.success()
        }
        try {
            authStore.withCurrent(accountId, pinned) {
                notifier.syncMissedAccounts(authStore.list().map { it.id })
                val update = notifier.updateAccountMissedState(
                    accountId,
                    page.unread,
                    refreshId,
                    redialBinding = CallSessionBinding.from(pinned),
                    immediate = true,
                )
                if (update.applied) {
                    CallHistoryEvents.publish(AccountUnreadState(accountId, page.unread, pinned))
                }
            }
        } catch (_: AccountStorageException) {
            return Result.success()
        }
        return Result.success()
    }

    companion object {
        const val AccountIdInputKey = "account_id"
    }
}

internal fun isTemporaryMissedCountRefreshFailure(error: Throwable): Boolean = when (error) {
    is IOException -> true
    is ApiException -> error.code == 408 || error.code == 429 || error.code in 500..599
    else -> false
}
