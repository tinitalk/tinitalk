package org.tinitalk.data

import org.junit.Assert.assertEquals
import org.junit.Test

class CallHistoryEventsTest {
    @Test
    fun `account event retains its opaque account identity`() {
        val events = CallHistoryEventBus()
        var received: AccountUnreadState? = null
        events.observeAccount { received = it }
        val expected = AccountUnreadState(
            AccountId("server-b"),
            CallUnreadState(unreadMissedCount = 1, unreadMissed = emptyList()),
        )

        events.publish(expected)

        assertEquals(expected, received)
    }
}
