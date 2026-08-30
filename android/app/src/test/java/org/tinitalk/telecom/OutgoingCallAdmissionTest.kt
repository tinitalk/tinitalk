package org.tinitalk.telecom

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutgoingCallAdmissionTest {
    @Test
    fun onlyNewOutgoingCallIsRejectedOffline() {
        assertTrue(rejectOfflineOutgoingStart(CallForegroundService.ActionStart, networkAvailable = false))
        assertFalse(rejectOfflineOutgoingStart(CallForegroundService.ActionAnswer, networkAvailable = false))
        assertFalse(rejectOfflineOutgoingStart(CallForegroundService.ActionStart, networkAvailable = true))
    }
}
