package org.tinitalk.push

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkRequest
import com.google.common.util.concurrent.ListenableFuture
import org.tinitalk.data.AccountId
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

internal interface PushRegistrationWorkEnqueuer {
    fun enqueueUniqueWork(
        name: String,
        policy: ExistingWorkPolicy,
        request: OneTimeWorkRequest,
    ): ListenableFuture<Operation.State.SUCCESS>

    fun cancelUniqueWork(name: String): ListenableFuture<Operation.State.SUCCESS>
}

internal class PushRegistrationScheduler internal constructor(
    private val enqueuer: PushRegistrationWorkEnqueuer,
) {
    constructor(context: Context) : this(WorkManagerPushRegistrationEnqueuer(context))

    fun enqueueRestore(accountId: AccountId) {
        enqueue(accountId, subscription = null, urgent = false, isRecovery = false)
    }

    fun enqueueUrgent(accountId: AccountId) {
        enqueue(accountId, subscription = null, urgent = true, isRecovery = false)
    }

    fun enqueueUrgent(accountId: AccountId, subscription: WebPushSubscription) {
        enqueue(accountId, subscription, urgent = true, isRecovery = false)
    }

    private fun enqueue(
        accountId: AccountId,
        subscription: WebPushSubscription?,
        urgent: Boolean,
        isRecovery: Boolean,
        generation: Long? = null,
    ) {
        val urgentGeneration = if (urgent) {
            generation ?: NextUrgentGeneration.incrementAndGet().also { next ->
                LatestUrgentGeneration.merge(accountId, next, ::maxOf)
            }
        } else {
            null
        }
        val requestBuilder = OneTimeWorkRequestBuilder<PushRegistrationWorker>()
            .setInputData(pushRegistrationInput(accountId, subscription))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS,
            )
        if (urgent) requestBuilder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        val request = requestBuilder.build()
        val result = try {
            synchronized(EnqueueLocks.computeIfAbsent(accountId) { Any() }) {
                if (urgent && LatestUrgentGeneration[accountId] != urgentGeneration) return
                enqueuer.enqueueUniqueWork(
                    uniqueWorkName(accountId),
                    if (urgent) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                    request,
                )
            }
        } catch (error: Exception) {
            recoverOrReport("enqueue", accountId, isRecovery, error) {
                enqueue(accountId, subscription, urgent, isRecovery = true, generation = urgentGeneration)
            }
            return
        }
        observe(result) { error ->
            recoverOrReport("enqueue", accountId, isRecovery, error) {
                enqueue(accountId, subscription, urgent, isRecovery = true, generation = urgentGeneration)
            }
        }
    }

    fun cancel(accountId: AccountId) {
        cancel(accountId, isRecovery = false)
    }

    private fun cancel(accountId: AccountId, isRecovery: Boolean) {
        val result = try {
            enqueuer.cancelUniqueWork(uniqueWorkName(accountId))
        } catch (error: Exception) {
            recoverOrReport("cancel", accountId, isRecovery, error) {
                cancel(accountId, isRecovery = true)
            }
            return
        }
        observe(result) { error ->
            recoverOrReport("cancel", accountId, isRecovery, error) {
                cancel(accountId, isRecovery = true)
            }
        }
    }

    private fun recoverOrReport(
        operation: String,
        accountId: AccountId,
        isRecovery: Boolean,
        error: Throwable,
        recover: () -> Unit,
    ) {
        if (!isRecovery) {
            recover()
            return
        }
        Log.e(LogTag, "$operation failed after account-scoped recovery for ${accountId.value}", error)
    }

    private fun observe(
        result: ListenableFuture<Operation.State.SUCCESS>,
        onFailure: (Throwable) -> Unit,
    ) {
        result.addListener(
            {
                try {
                    // The listener runs only after completion, so this read never waits on its caller.
                    result.get()
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    onFailure(error)
                } catch (error: ExecutionException) {
                    onFailure(error.cause ?: error)
                } catch (error: CancellationException) {
                    onFailure(error)
                }
            },
            DirectExecutor,
        )
    }

    companion object {
        private const val UniqueWorkNamePrefix = "webpush-registration:"
        private const val LogTag = "PushRegistration"

        private val EnqueueLocks = ConcurrentHashMap<AccountId, Any>()
        private val LatestUrgentGeneration = ConcurrentHashMap<AccountId, Long>()
        private val NextUrgentGeneration = AtomicLong()
        internal fun uniqueWorkName(accountId: AccountId): String = UniqueWorkNamePrefix + accountId.value

        private val DirectExecutor = Executor(Runnable::run)
    }
}

private fun pushRegistrationInput(
    accountId: AccountId,
    subscription: WebPushSubscription?,
): Data = Data.Builder()
    .putString(PushRegistrationWorker.AccountIdInputKey, accountId.value)
    .putBoolean(PushRegistrationWorker.ForceRefreshInputKey, subscription == null)
    .apply {
        subscription?.let {
            putString(PushRegistrationWorker.EndpointInputKey, it.endpoint)
            putString(PushRegistrationWorker.P256dhInputKey, it.keys.p256dh)
            putString(PushRegistrationWorker.AuthInputKey, it.keys.auth)
        }
    }
    .build()

private class WorkManagerPushRegistrationEnqueuer(context: Context) : PushRegistrationWorkEnqueuer {
    private val workManager = WorkManager.getInstance(context)

    override fun enqueueUniqueWork(
        name: String,
        policy: ExistingWorkPolicy,
        request: OneTimeWorkRequest,
    ): ListenableFuture<Operation.State.SUCCESS> =
        workManager.enqueueUniqueWork(name, policy, request).result

    override fun cancelUniqueWork(name: String): ListenableFuture<Operation.State.SUCCESS> =
        workManager.cancelUniqueWork(name).result
}
