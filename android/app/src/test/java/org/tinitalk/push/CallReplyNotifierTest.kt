package org.tinitalk.push

import android.app.Notification
import android.app.NotificationManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.tinitalk.call.*
import org.tinitalk.contactPeerFromIntent
import org.tinitalk.data.AccountId
import org.tinitalk.data.AccountPeerKey

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CallReplyNotifierTest {
    @Test
    fun notificationOpensCorrectContactAndSessionRemovalRevokesOnlyItsResults() {
        val context = RuntimeEnvironment.getApplication()
        val binding = CallSessionBinding("https://a.example", "alice", "session", "config")
        val key = AccountCallKey(AccountId("a"), "call")
        val result = CallReplyResult(key, CallPeer("Bob", "bob"), CallReplyCode.WillCallBack, binding)
        val store = CallReplyResultStore(context)
        val notifier = CallReplyNotifier(context)
        store.save(result)
        notifier.show(result)
        val manager = context.getSystemService(NotificationManager::class.java)
        val active = manager.activeNotifications.single { it.id == CallReplyNotifier.NotificationId }
        assertEquals("Я вам перезвоню", active.notification.extras.getCharSequence(Notification.EXTRA_TEXT))
        val intent = Shadows.shadowOf(active.notification.contentIntent).savedIntent
        assertEquals(AccountPeerKey(key.accountId, "bob"), contactPeerFromIntent(intent))
        notifier.clearForSession(AccountId("other"), binding)
        store.clearForSession(key.accountId, binding.copy(sessionId = "old"))
        assertNotNull(store.load())
        assertEquals(1, manager.activeNotifications.count { it.id == CallReplyNotifier.NotificationId })
        notifier.clearForSession(key.accountId, binding)
        store.clearForSession(key.accountId, binding)
        assertNull(store.load())
        assertEquals(0, manager.activeNotifications.count { it.id == CallReplyNotifier.NotificationId })
    }
}
