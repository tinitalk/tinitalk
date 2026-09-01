package org.tinitalk.push

import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.Operation
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkRequest
import androidx.concurrent.futures.ResolvableFuture
import com.google.common.util.concurrent.ListenableFuture
import org.tinitalk.data.AccountId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PushRegistrationSchedulerTest {
    @Test
    fun startupRestoreKeepsExistingWorkWithoutUsingExpeditedQuota() {
        val enqueuer = RecordingPushRegistrationWorkEnqueuer()
        val scheduler = PushRegistrationScheduler(enqueuer)

        scheduler.enqueueRestore(AccountId("account-a"))
        scheduler.enqueueRestore(AccountId("account-b"))

        assertEquals(2, enqueuer.requests.size)
        enqueuer.requests.zip(listOf("account-a", "account-b")).forEach { (call, accountId) ->
            assertEquals("webpush-registration:$accountId", call.name)
            assertEquals(ExistingWorkPolicy.KEEP, call.policy)
            assertEquals(accountId, call.request.workSpec.input.getString(PushRegistrationWorker.AccountIdInputKey))
            assertTrue(call.request.workSpec.input.getBoolean("force_refresh", false))
            assertFalse(call.request.workSpec.expedited)
            assertEquals(NetworkType.CONNECTED, call.request.workSpec.constraints.requiredNetworkType)
            assertEquals(BackoffPolicy.EXPONENTIAL, call.request.workSpec.backoffPolicy)
            assertEquals(WorkRequest.MIN_BACKOFF_MILLIS, call.request.workSpec.backoffDelayDuration)
            assertEquals(PushRegistrationWorker::class.java.name, call.request.workSpec.workerClassName)
        }
    }

    @Test
    fun endpointRefreshCarriesTheExactSubscription() {
        val enqueuer = RecordingPushRegistrationWorkEnqueuer()
        val subscription = WebPushSubscription(
            endpoint = "https://fcm.googleapis.com/fcm/send/endpoint-a",
            keys = WebPushKeys("p256dh-a", "auth-a"),
        )

        PushRegistrationScheduler(enqueuer).enqueueUrgent(AccountId("account-a"), subscription)

        val input = enqueuer.requests.single().request.workSpec.input
        assertEquals(ExistingWorkPolicy.REPLACE, enqueuer.requests.single().policy)
        assertTrue(enqueuer.requests.single().request.workSpec.expedited)
        assertEquals(
            OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST,
            enqueuer.requests.single().request.workSpec.outOfQuotaPolicy,
        )
        assertEquals(
            "https://fcm.googleapis.com/fcm/send/endpoint-a",
            input.getString(PushRegistrationWorker.EndpointInputKey),
        )
        assertEquals("p256dh-a", input.getString(PushRegistrationWorker.P256dhInputKey))
        assertEquals("auth-a", input.getString(PushRegistrationWorker.AuthInputKey))
        assertEquals(false, input.getBoolean(PushRegistrationWorker.ForceRefreshInputKey, true))
    }

    @Test
    fun registrationRecoveryReplacesOldWorkAndRunsExpedited() {
        val enqueuer = RecordingPushRegistrationWorkEnqueuer()

        PushRegistrationScheduler(enqueuer).enqueueUrgent(AccountId("account-a"))

        val call = enqueuer.requests.single()
        assertEquals(ExistingWorkPolicy.REPLACE, call.policy)
        assertTrue(call.request.workSpec.expedited)
        assertTrue(call.request.workSpec.input.getBoolean(PushRegistrationWorker.ForceRefreshInputKey, false))
    }

    @Test
    fun lateFailureOfOldEndpointDoesNotReplaceNewerEndpoint() {
        val oldEnqueue = ResolvableFuture.create<Operation.State.SUCCESS>()
        val enqueuer = RecordingPushRegistrationWorkEnqueuer(
            enqueueResults = ArrayDeque(listOf(oldEnqueue, successfulOperation())),
        )
        val scheduler = PushRegistrationScheduler(enqueuer)

        scheduler.enqueueUrgent(
            AccountId("account-a"),
            WebPushSubscription("https://push.example/old", WebPushKeys("old-key", "old-auth")),
        )
        scheduler.enqueueUrgent(
            AccountId("account-a"),
            WebPushSubscription("https://push.example/new", WebPushKeys("new-key", "new-auth")),
        )
        oldEnqueue.setException(IllegalStateException("late enqueue failure"))

        assertEquals(2, enqueuer.requests.size)
        assertEquals(
            listOf("https://push.example/old", "https://push.example/new"),
            enqueuer.requests.map { it.request.workSpec.input.getString(PushRegistrationWorker.EndpointInputKey) },
        )
    }

    @Test
    fun startupRestoreDoesNotSuppressUrgentEndpointRecovery() {
        val urgentEnqueue = ResolvableFuture.create<Operation.State.SUCCESS>()
        val enqueuer = RecordingPushRegistrationWorkEnqueuer(
            enqueueResults = ArrayDeque(
                listOf(urgentEnqueue, successfulOperation(), successfulOperation()),
            ),
        )
        val scheduler = PushRegistrationScheduler(enqueuer)

        scheduler.enqueueUrgent(
            AccountId("account-a"),
            WebPushSubscription("https://push.example/current", WebPushKeys("key", "auth")),
        )
        scheduler.enqueueRestore(AccountId("account-a"))
        urgentEnqueue.setException(IllegalStateException("late enqueue failure"))

        assertEquals(
            listOf(ExistingWorkPolicy.REPLACE, ExistingWorkPolicy.KEEP, ExistingWorkPolicy.REPLACE),
            enqueuer.requests.map(EnqueueCall::policy),
        )
        assertEquals(
            listOf("https://push.example/current", null, "https://push.example/current"),
            enqueuer.requests.map { it.request.workSpec.input.getString(PushRegistrationWorker.EndpointInputKey) },
        )
        assertTrue(enqueuer.requests.last().request.workSpec.expedited)
    }

    @Test
    fun cancelsOnlyTheSelectedAccountsUniqueWork() {
        val enqueuer = RecordingPushRegistrationWorkEnqueuer()

        PushRegistrationScheduler(enqueuer).cancel(AccountId("account-a"))

        assertEquals(listOf("webpush-registration:account-a"), enqueuer.cancelled)
    }

    @Test
    fun asynchronousEnqueueFailureRetriesTheSameAccountWork() {
        val first = ResolvableFuture.create<Operation.State.SUCCESS>()
        val recovered = ResolvableFuture.create<Operation.State.SUCCESS>()
        val enqueuer = RecordingPushRegistrationWorkEnqueuer(
            enqueueResults = ArrayDeque(listOf(first, recovered)),
        )
        val scheduler = PushRegistrationScheduler(enqueuer)

        scheduler.enqueueUrgent(AccountId("account-a"))
        assertEquals(1, enqueuer.requests.size)

        first.setException(IllegalStateException("asynchronous WorkManager failure"))
        assertEquals(2, enqueuer.requests.size)
        recovered.set(Operation.SUCCESS)

        assertEquals(
            listOf(
                "webpush-registration:account-a",
                "webpush-registration:account-a",
            ),
            enqueuer.requests.map(EnqueueCall::name),
        )
        assertEquals(
            listOf("account-a", "account-a"),
            enqueuer.requests.map { call ->
                requireNotNull(call.request.workSpec.input.getString(PushRegistrationWorker.AccountIdInputKey))
            },
        )
        assertEquals(2, enqueuer.requests.map { it.request.id }.distinct().size)
    }

    @Test
    fun asynchronousCancelFailureRetriesOnlyTheSelectedAccountCancellation() {
        val first = ResolvableFuture.create<Operation.State.SUCCESS>()
        val recovered = ResolvableFuture.create<Operation.State.SUCCESS>()
        val enqueuer = RecordingPushRegistrationWorkEnqueuer(
            cancelResults = ArrayDeque(listOf(first, recovered)),
        )

        PushRegistrationScheduler(enqueuer).cancel(AccountId("account-a"))
        assertEquals(1, enqueuer.cancelled.size)

        first.setException(IllegalStateException("asynchronous WorkManager failure"))
        assertEquals(
            listOf(
                "webpush-registration:account-a",
                "webpush-registration:account-a",
            ),
            enqueuer.cancelled,
        )
        recovered.set(Operation.SUCCESS)
    }

    @Test
    fun synchronousEnqueueFailureUsesTheSameBoundedAccountRecovery() {
        val enqueuer = RecordingPushRegistrationWorkEnqueuer(
            enqueueFailures = ArrayDeque(listOf(IllegalStateException("synchronous failure"))),
        )

        PushRegistrationScheduler(enqueuer).enqueueUrgent(AccountId("account-a"))

        assertEquals(2, enqueuer.requests.size)
        assertEquals(2, enqueuer.requests.map { it.request.id }.distinct().size)
        assertEquals(
            listOf("account-a", "account-a"),
            enqueuer.requests.map { call ->
                requireNotNull(call.request.workSpec.input.getString(PushRegistrationWorker.AccountIdInputKey))
            },
        )
    }

    @Test
    fun synchronousCancelFailureUsesTheSameBoundedAccountRecovery() {
        val enqueuer = RecordingPushRegistrationWorkEnqueuer(
            cancelFailures = ArrayDeque(listOf(IllegalStateException("synchronous failure"))),
        )

        PushRegistrationScheduler(enqueuer).cancel(AccountId("account-a"))

        assertEquals(
            listOf(
                "webpush-registration:account-a",
                "webpush-registration:account-a",
            ),
            enqueuer.cancelled,
        )
    }
}

private data class EnqueueCall(
    val name: String,
    val policy: ExistingWorkPolicy,
    val request: OneTimeWorkRequest,
)

private class RecordingPushRegistrationWorkEnqueuer(
    private val enqueueResults: ArrayDeque<ResolvableFuture<Operation.State.SUCCESS>> = ArrayDeque(),
    private val cancelResults: ArrayDeque<ResolvableFuture<Operation.State.SUCCESS>> = ArrayDeque(),
    private val enqueueFailures: ArrayDeque<Exception> = ArrayDeque(),
    private val cancelFailures: ArrayDeque<Exception> = ArrayDeque(),
) : PushRegistrationWorkEnqueuer {
    val requests = mutableListOf<EnqueueCall>()
    val cancelled = mutableListOf<String>()

    override fun enqueueUniqueWork(
        name: String,
        policy: ExistingWorkPolicy,
        request: OneTimeWorkRequest,
    ): ListenableFuture<Operation.State.SUCCESS> {
        requests += EnqueueCall(name, policy, request)
        enqueueFailures.removeFirstOrNull()?.let { throw it }
        return enqueueResults.removeFirstOrNull() ?: successfulOperation()
    }

    override fun cancelUniqueWork(name: String): ListenableFuture<Operation.State.SUCCESS> {
        cancelled += name
        cancelFailures.removeFirstOrNull()?.let { throw it }
        return cancelResults.removeFirstOrNull() ?: successfulOperation()
    }

}

private fun successfulOperation() = ResolvableFuture.create<Operation.State.SUCCESS>().apply {
    set(Operation.SUCCESS)
}
