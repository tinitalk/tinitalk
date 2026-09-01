package org.tinitalk.push

import org.tinitalk.data.AccountId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebPushEndpointHandoffTest {
    @Test
    fun endpointCompletesOnlyMatchingAccountWaiter() {
        val handoff = WebPushEndpointHandoff()
        val accountA = AccountId("account-a")
        val accountB = AccountId("account-b")
        val futureA = handoff.begin(accountA)
        val futureB = handoff.begin(accountB)
        val subscription = WebPushSubscription(
            "https://fcm.googleapis.com/fcm/send/b",
            WebPushKeys("p256dh-b", "auth-b"),
        )

        assertTrue(handoff.complete(accountB, subscription))

        assertFalse(futureA.isDone)
        assertEquals(subscription, futureB.get())
    }
}
