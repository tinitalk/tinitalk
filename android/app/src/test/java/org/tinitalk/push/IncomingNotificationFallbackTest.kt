package org.tinitalk.push

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.tinitalk.call.CallSessionBinding
import org.tinitalk.data.AccountId
import org.tinitalk.data.ContactAddress
import org.tinitalk.data.ContactPhotoReader
import org.tinitalk.telecom.IncomingCallController
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class IncomingNotificationFallbackTest {
    private val context = RuntimeEnvironment.getApplication()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val incoming = IncomingCallController()
    private val invite = IncomingInvite(
        AccountId("fallback"), CallSessionBinding("https://example.test", "alice", "session", "config"),
        "fallback-call", "Bob", Instant.now().plusSeconds(45), callerLogin = "bob",
    )
    private val queuedPhotos = mutableListOf<Runnable>()
    private val reader = object : ContactPhotoReader {
        override val revision: StateFlow<Long> = MutableStateFlow(0L)
        override fun peekBitmap(address: ContactAddress, targetPixels: Int): Bitmap? = null
        override fun loadBitmap(address: ContactAddress, targetPixels: Int) =
            Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
    }
    private val loader = ContactPhotoNotificationLoader(reader) { queuedPhotos += it }
    private val notifier = IncomingCallNotifier(context, loader)

    @Before fun setUp() { incoming.admitIncoming(context, invite) }

    @After fun tearDown() {
        incoming.finishTerminalPresentation(context, invite.owner) { notifier.cancel() }
    }

    @Test fun deniedServiceStartStillRestoresAnActionableNotification() {
        var attemptedRestore = false
        val blocked = object : ContextWrapper(context) {
            override fun startForegroundService(service: Intent): ComponentName? {
                attemptedRestore = true
                throw IllegalStateException("Background start denied")
            }
        }
        IncomingCallScreenState.shown(invite.owner)
        IncomingCallNotifier(blocked, loader).fullScreenHidden(invite)

        assertTrue(attemptedRestore)
        assertFalse(IncomingCallScreenState.isShowing(invite.owner))
        val notification = manager.activeNotifications.single().notification
        assertPlainNotification(notification)
        assertEquals("Bob", notification.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertEquals(2, notification.actions.size)
        assertNotNull(notification.contentIntent)
        assertEquals(invite.owner, incoming.load(context)?.invite?.owner)
        assertFalse(incoming.isTerminal(context, invite.owner))
    }

    @Test fun latePhotoCannotRecreateNotificationHiddenByTheCallScreen() {
        notifier.show(invite)
        IncomingCallScreenState.shown(invite.owner)
        manager.cancel(IncomingCallNotifier.NotificationId)
        finishPhoto()

        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test fun latePhotoAfterCancellationCannotRecreateNotification() {
        notifier.show(invite)
        incoming.finishTerminalPresentation(context, invite.owner) { notifier.cancel() }
        finishPhoto()

        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test fun photoFromForegroundAttemptCannotUpgradeStandaloneFallbackToCallStyle() {
        notifier.presentIncoming(invite, IncomingCallPresentationMode.InApp, foregroundService = true) {
            // Simulate rejected foreground promotion; its photo request is still pending.
        }
        notifier.show(invite)
        finishPhoto()

        val notification = manager.activeNotifications.single().notification
        assertPlainNotification(notification)
        assertNotNull(notification.getLargeIcon())
        assertTrue(notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, manager.getNotificationChannel(notification.channelId).importance)
    }

    private fun finishPhoto() {
        queuedPhotos.toList().forEach(Runnable::run)
        queuedPhotos.clear()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    private fun assertPlainNotification(notification: Notification) {
        assertFalse(notification.extras.getString(Notification.EXTRA_TEMPLATE) == Notification.CallStyle::class.java.name)
    }
}
