package org.tinitalk.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CallHistoryEventsTest {
    @Test
    fun `publishes fresh unread state only to registered observers`() {
        val events = CallHistoryEventBus()
        var received: CallUnreadState? = null
        val observer: (CallUnreadState) -> Unit = { received = it }
        events.observe(observer)
        val unread = CallUnreadState(unreadMissedCount = 2, unreadMissed = emptyList())

        events.publish(unread)
        assertEquals(unread, received)

        events.removeObserver(observer)
        received = null
        events.publish(CallUnreadState(unreadMissedCount = 3, unreadMissed = emptyList()))
        assertNull(received)
    }
}
