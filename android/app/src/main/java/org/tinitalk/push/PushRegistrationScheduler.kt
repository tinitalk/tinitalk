package org.tinitalk.push

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import java.util.concurrent.TimeUnit

internal interface PushRegistrationWorkEnqueuer {
    fun enqueueUniqueWork(
        name: String,
        policy: ExistingWorkPolicy,
        request: OneTimeWorkRequest,
    )
}

internal class PushRegistrationScheduler internal constructor(
    private val enqueuer: PushRegistrationWorkEnqueuer,
) {
    constructor(context: Context) : this(WorkManagerPushRegistrationEnqueuer(context))

    fun enqueue() {
        val request = OneTimeWorkRequestBuilder<PushRegistrationWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS,
            )
            .build()
        enqueuer.enqueueUniqueWork(UniqueWorkName, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    companion object {
        const val UniqueWorkName = "firebase-installation-registration"
    }
}

private class WorkManagerPushRegistrationEnqueuer(context: Context) : PushRegistrationWorkEnqueuer {
    private val workManager = WorkManager.getInstance(context)

    override fun enqueueUniqueWork(
        name: String,
        policy: ExistingWorkPolicy,
        request: OneTimeWorkRequest,
    ) {
        workManager.enqueueUniqueWork(name, policy, request)
    }
}
