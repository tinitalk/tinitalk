package org.tinitalk.telecom

import android.app.Notification
import android.app.Person
import android.graphics.Bitmap
import org.tinitalk.call.CallDirection
import org.tinitalk.call.CallPeer
import org.tinitalk.call.CallPhase
import org.tinitalk.call.CallUiState
import org.tinitalk.data.AccountId
import org.tinitalk.data.ContactAddress
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CallForegroundServiceNotificationTest {
    @Test
    fun ongoingNotificationUsesPeerPhotoForPersonAndLargeIcon() {
        val controller = Robolectric.buildService(CallForegroundService::class.java).create()
        val service = controller.get()
        val address = ContactAddress.of("https://calls.example", "alex")
        val state = CallUiState(
            accountId = AccountId("account-a"),
            callId = "call-1",
            peer = CallPeer("Алексей", "alex", address),
            direction = CallDirection.Outgoing,
            phase = CallPhase.Active,
        )
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)

        val notification = service.notificationForTest(state, bitmap)
        val person = notification.extras.getParcelable(Notification.EXTRA_CALL_PERSON, Person::class.java)

        assertNotNull(notification.getLargeIcon())
        assertNotNull(person?.icon)
    }

    private fun CallForegroundService.notificationForTest(state: CallUiState, bitmap: Bitmap): Notification {
        val method = CallForegroundService::class.java.getDeclaredMethod(
            "notification",
            CallUiState::class.java,
            Bitmap::class.java,
        )
        method.isAccessible = true
        return method.invoke(this, state, bitmap) as Notification
    }
}
