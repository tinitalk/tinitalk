package org.tinitalk.push

import org.tinitalk.data.CallUnreadState
import org.junit.Assert.assertEquals
import org.junit.Test

class MissedCallAcknowledgerTest {
    @Test
    fun `redial marks only the selected contact through its latest history item`() {
        var marked: Pair<String, Long>? = null
        val expected = CallUnreadState(unreadMissedCount = 2, unreadMissed = emptyList())

        val result = acknowledgeLatestMissedCall(
            login = "anna",
            loadLatestId = { login: String -> if (login == "anna") 42L else null },
            markRead = { login: String, throughId: Long ->
                marked = login to throughId
                expected
            },
        )

        assertEquals("anna" to 42L, marked)
        assertEquals(expected, result)
    }
}
