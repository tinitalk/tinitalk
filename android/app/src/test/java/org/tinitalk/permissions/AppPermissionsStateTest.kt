package org.tinitalk.permissions

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppPermissionsStateTest {
    @Test
    fun allRequiredGrantedNeedsEveryPermission() {
        assertTrue(
            AppPermissionsState(
                notificationsGranted = true,
                microphoneGranted = true,
                fullScreenIntentGranted = true,
            ).allRequiredGranted,
        )
        assertFalse(AppPermissionsState(false, true, true).allRequiredGranted)
        assertFalse(AppPermissionsState(true, false, true).allRequiredGranted)
        assertFalse(AppPermissionsState(true, true, false).allRequiredGranted)
    }
}
