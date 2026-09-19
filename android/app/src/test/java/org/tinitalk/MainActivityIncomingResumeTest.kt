package org.tinitalk

import android.app.NotificationManager
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.tinitalk.call.CallSessionBinding
import org.tinitalk.data.AccountId
import org.tinitalk.push.IncomingCallNotifier
import org.tinitalk.push.IncomingInvite
import org.tinitalk.telecom.IncomingCallController
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainActivityIncomingResumeTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun openingAppDuringIncomingCallOpensItsScreenWithoutRepostingNotification() {
        val activityController = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = activityController.get()
        val incoming = IncomingCallController()
        val notifier = IncomingCallNotifier(activity)
        val invite = IncomingInvite(
            AccountId("account"), CallSessionBinding("https://talk.example", "alice", "session", "config"),
            "incoming", "Bob", Instant.now().plusSeconds(60),
        )
        activityController.pause().stop()
        incoming.admitIncoming(activity, invite)
        notifier.show(invite)
        val manager = activity.getSystemService(NotificationManager::class.java)
        val original = manager.activeNotifications.single().notification
        try {
            activityController.start().resume()

            val launched = shadowOf(activity).nextStartedActivity
            assertNotNull("Opening the app must also open the already ringing call", launched)
            assertEquals(CallActivity::class.java.name, launched.component?.className)
            assertEquals(IncomingCallController.ActionIncoming, launched.action)
            assertEquals(invite, IncomingCallController.inviteFrom(launched))
            assertSame("Navigation must not restart ringing", original, manager.activeNotifications.single().notification)
        } finally {
            incoming.finishTerminalPresentation(activity, invite.owner) { notifier.cancel() }
            activityController.pause().stop().destroy()
        }
    }
}
