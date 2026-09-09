package org.tinitalk.telecom

import android.app.Notification
import android.app.NotificationManager
import android.graphics.Bitmap
import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.tinitalk.call.CallSessionBinding
import org.tinitalk.data.AccountId
import org.tinitalk.push.IncomingCallNotifier
import org.tinitalk.push.IncomingCallSilenceStore
import org.tinitalk.push.IncomingCallAlertHandoff
import org.tinitalk.push.incomingCallSilenceStore
import org.tinitalk.push.ContactPhotoNotificationLoader
import org.tinitalk.data.ContactPhotoReader
import org.tinitalk.data.ContactAddress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.tinitalk.push.IncomingInvite
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class IncomingReplyRingingTest {
    @Test
    fun photoCompletingAfterExpansionPublishesOnlySilentNotification() {
        val context = RuntimeEnvironment.getApplication()
        val controller = IncomingCallController()
        val current = invite("photo-alert").copy(callerLogin = "bob")
        controller.admitIncoming(context, current)
        val queued = mutableListOf<Runnable>()
        val reader = object : ContactPhotoReader {
            override val revision: StateFlow<Long> = MutableStateFlow(0L)
            override fun peekBitmap(address: ContactAddress, targetPixels: Int): Bitmap? = null
            override fun loadBitmap(address: ContactAddress, targetPixels: Int): Bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        }
        val notifier = IncomingCallNotifier(context, ContactPhotoNotificationLoader(reader) { queued += it })
        notifier.show(current)
        assertEquals(1, queued.size)
        assertTrue(notifier.silence(current))
        queued.single().run()
        val notification = context.getSystemService(NotificationManager::class.java).activeNotifications
            .single { it.id == IncomingCallNotifier.NotificationId }.notification
        assertEquals(Notification.GROUP_ALERT_SUMMARY, notification.groupAlertBehavior)
        assertEquals("incoming_call_silenced", notification.group)
        controller.expirePending(context, current.owner, now = current.expiresAt) { notifier.cancel() }
    }

    @Test
    fun expandedPanelStopsBothPlayersAndNoLifecyclePathRestartsThem() {
        val context = RuntimeEnvironment.getApplication()
        val controller = IncomingCallController()
        val current = invite("alert-lifecycle")
        controller.admitIncoming(context, current)
        var vibrationStarts = 0
        var ringtoneStarts = 0
        var vibrationStops = 0
        var ringtoneStops = 0
        var dismissals = 0
        val notifier = IncomingCallNotifier(context, null, IncomingCallAlertHandoff(
            startVibration = { vibrationStarts++ }, startRingtone = { ringtoneStarts++ },
            dismissNotification = { dismissals++ },
            isSilenced = { incomingCallSilenceStore(context).isSilenced(it) },
            stopVibration = { assertEquals(current.owner, it); vibrationStops++ },
            stopRingtone = { assertEquals(current.owner, it); ringtoneStops++ },
        ))
        notifier.fullScreenShown(current)
        assertEquals(1, vibrationStarts)
        assertEquals(1, ringtoneStarts)
        assertEquals(1, dismissals)
        assertTrue(notifier.silence(current))
        assertEquals(1, vibrationStops)
        assertEquals(1, ringtoneStops)
        repeat(2) {
            notifier.fullScreenHidden(current)
            val notification = requireNotNull(notifier.buildIncomingNotification(current))
            assertEquals(Notification.GROUP_ALERT_SUMMARY, notification.groupAlertBehavior)
            notifier.fullScreenShown(current)
        }
        assertEquals(1, vibrationStarts)
        assertEquals(1, ringtoneStarts)
        assertEquals(3, dismissals)
        notifier.fullScreenShown(current.copy(sessionBinding = current.sessionBinding.copy(sessionId = "stale")))
        assertEquals(3, dismissals)
        controller.expirePending(context, current.owner, now = current.expiresAt) { notifier.cancel() }
        val next = invite("next-alert")
        controller.admitIncoming(context, next)
        notifier.fullScreenShown(next)
        assertEquals(2, vibrationStarts)
        assertEquals(2, ringtoneStarts)
        val nextNotification = requireNotNull(notifier.buildIncomingNotification(next))
        assertNotEquals("incoming_call_silenced", nextNotification.group)
        controller.expirePending(context, next.owner, now = next.expiresAt) { notifier.cancel() }
    }

    @Test
    fun silenceSurvivesRecreationButNeverLeaksToAnotherCallOrSession() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("silence-test", Context.MODE_PRIVATE)
        val invite = invite("one")
        val store = IncomingCallSilenceStore(prefs)
        assertFalse(store.isSilenced(invite))
        store.silence(invite)
        val restored = IncomingCallSilenceStore(prefs)
        assertTrue(restored.isSilenced(invite))
        assertFalse(restored.isSilenced(invite.copy(callId = "two")))
        assertFalse(restored.isSilenced(invite.copy(accountId = AccountId("other"))))
        assertFalse(restored.isSilenced(invite.copy(sessionBinding = invite.sessionBinding.copy(sessionId = "new"))))
        assertFalse(restored.isSilenced(invite, now = invite.expiresAt))
    }

    @Test
    fun silencingKeepsCallPendingAndRepeatedNotificationsUseSilentChannel() {
        val context = RuntimeEnvironment.getApplication()
        val incoming = IncomingCallController()
        val invite = invite("silenced")
        incoming.admitIncoming(context, invite)
        val notifier = IncomingCallNotifier(context)
        assertFalse(notifier.silence(invite.copy(callId = "stale")))
        assertTrue(notifier.silence(invite))
        assertEquals(invite.owner, incoming.load(context)?.invite?.owner)
        val notification = requireNotNull(IncomingCallNotifier(context).buildIncomingNotification(invite))
        assertEquals(Notification.GROUP_ALERT_SUMMARY, notification.groupAlertBehavior)
        assertEquals("incoming_call_silenced", notification.group)
        incoming.expirePending(context, invite.owner, now = invite.expiresAt) { notifier.cancel() }
        assertFalse(notifier.silence(invite))
    }

    private fun invite(id: String) = IncomingInvite(
        AccountId("account"), CallSessionBinding("https://example.test", "alice", "session", "config"),
        id, "Bob", Instant.now().plusSeconds(45),
    )
}
