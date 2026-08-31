package org.tinitalk.push

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.workDataOf
import com.google.common.util.concurrent.ListenableFuture
import org.tinitalk.data.AccountId
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

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

    fun enqueue(accountId: AccountId) {
        enqueue(accountId, isRecovery = false)
    }

    private fun enqueue(accountId: AccountId, isRecovery: Boolean) {
        val request = OneTimeWorkRequestBuilder<PushRegistrationWorker>()
            .setInputData(workDataOf(PushRegistrationWorker.AccountIdInputKey to accountId.value))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS,
            )
            .build()
        val result = try {
            enqueuer.enqueueUniqueWork(
                uniqueWorkName(accountId),
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request,
            )
        } catch (error: Exception) {
            recoverOrReport("enqueue", accountId, isRecovery, error) {
                enqueue(accountId, isRecovery = true)
            }
            return
        }
        observe(result) { error ->
            recoverOrReport("enqueue", accountId, isRecovery, error) {
                enqueue(accountId, isRecovery = true)
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

        internal fun uniqueWorkName(accountId: AccountId): String = UniqueWorkNamePrefix + accountId.value

        private val DirectExecutor = Executor(Runnable::run)
    }
}

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
