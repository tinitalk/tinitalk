package org.tinitalk.push

import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkRequest
import org.junit.Assert.assertEquals
import org.junit.Test

class PushRegistrationSchedulerTest {
    @Test
    fun serializesDataFreeNetworkWorkAndAppendsNewerGenerations() {
        val enqueuer = RecordingPushRegistrationWorkEnqueuer()
        val scheduler = PushRegistrationScheduler(enqueuer)

        scheduler.enqueue()
        scheduler.enqueue()

        assertEquals(2, enqueuer.requests.size)
        enqueuer.requests.forEach { call ->
            assertEquals(PushRegistrationScheduler.UniqueWorkName, call.name)
            assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE, call.policy)
            assertEquals(0, call.request.workSpec.input.size())
            assertEquals(NetworkType.CONNECTED, call.request.workSpec.constraints.requiredNetworkType)
            assertEquals(BackoffPolicy.EXPONENTIAL, call.request.workSpec.backoffPolicy)
            assertEquals(WorkRequest.MIN_BACKOFF_MILLIS, call.request.workSpec.backoffDelayDuration)
            assertEquals(PushRegistrationWorker::class.java.name, call.request.workSpec.workerClassName)
        }
    }
}

private data class EnqueueCall(
    val name: String,
    val policy: ExistingWorkPolicy,
    val request: OneTimeWorkRequest,
)

private class RecordingPushRegistrationWorkEnqueuer : PushRegistrationWorkEnqueuer {
    val requests = mutableListOf<EnqueueCall>()

    override fun enqueueUniqueWork(
        name: String,
        policy: ExistingWorkPolicy,
        request: OneTimeWorkRequest,
    ) {
        requests += EnqueueCall(name, policy, request)
    }
}
