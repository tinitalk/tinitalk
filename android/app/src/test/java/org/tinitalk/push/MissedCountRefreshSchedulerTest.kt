package org.tinitalk.push

import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import org.tinitalk.data.AccountId
import org.tinitalk.data.ApiException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test

class MissedCountRefreshSchedulerTest {
    @Test
    fun enqueuesAccountScopedNetworkWork() {
        val calls = mutableListOf<MissedRefreshEnqueueCall>()
        val accountId = AccountId("account-a")

        MissedCountRefreshScheduler { name, policy, request ->
            calls += MissedRefreshEnqueueCall(name, policy, request)
        }.enqueue(accountId)

        val call = calls.single()
        assertEquals("missed-count-refresh:account-a", call.name)
        assertEquals(ExistingWorkPolicy.REPLACE, call.policy)
        assertEquals(NetworkType.CONNECTED, call.request.workSpec.constraints.requiredNetworkType)
        val input = call.request.workSpec.input
        assertEquals("account-a", input.getString(MissedCountRefreshWorker.AccountIdInputKey))
    }

    @Test
    fun retriesOnlyTemporaryRefreshFailures() {
        val cases = listOf(
            IOException("offline") to true,
            ApiException(408, "timeout") to true,
            ApiException(429, "rate limited") to true,
            ApiException(503, "unavailable") to true,
            ApiException(400, "bad request") to false,
            ApiException(403, "forbidden") to false,
            ApiException(404, "not found") to false,
            IllegalArgumentException("invalid response") to false,
        )

        cases.forEach { (error, expected) ->
            assertEquals(error.toString(), expected, isTemporaryMissedCountRefreshFailure(error))
        }
    }
}

private data class MissedRefreshEnqueueCall(
    val name: String,
    val policy: ExistingWorkPolicy,
    val request: OneTimeWorkRequest,
)
