package org.tinitalk.push

import android.app.NotificationManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.tinitalk.AppActivityVisibility
import org.tinitalk.CallActivity
import org.tinitalk.call.*
import org.tinitalk.data.AccountId
import org.tinitalk.data.ContactAddress

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class CallReplyNotificationPresentationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = RuntimeEnvironment.getApplication()
    private val key = AccountCallKey(AccountId("reply-account"), "reply-call")
    private val peer = CallPeer("Bob", "bob", ContactAddress.of("https://reply.example", "bob"))
    private val result = CallReplyResult(key, peer, CallReplyCode.WillCallBack)
    private val notifier = CallReplyNotifier(context)
    private val manager = context.getSystemService(NotificationManager::class.java)

    @After fun cleanup() {
        manager.cancelAll()
        CallReplyResultStore(context).clear()
        CallUiStateStore.reset()
        CallServiceState.reset()
    }

    @Test fun anotherForegroundActivityDoesNotHideReplyNotification() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            assertTrue(AppActivityVisibility.isVisible)
            notifier.showUnlessDisplayed(result)
            assertEquals(1, replyNotifications())
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun matchingCallSuppressesNotificationOnlyWhileResumed() {
        CallUiStateStore.begin(key, peer, CallDirection.Outgoing, CallPhase.Ringing)
        val activity = Robolectric.buildActivity(CallActivity::class.java, CallActivity.ongoingIntent(context)).setup()
        try {
            notifier.showUnlessDisplayed(result)
            assertEquals(0, replyNotifications())
            activity.pause().stop()
            notifier.showUnlessDisplayed(result)
            assertEquals(1, replyNotifications())
        } finally { activity.destroy() }
    }

    @Test fun visibleCallCannotSuppressReplyFromAnotherAccountOrCall() {
        CallUiStateStore.begin(key, peer, CallDirection.Outgoing, CallPhase.Ringing)
        val activity = Robolectric.buildActivity(CallActivity::class.java, CallActivity.ongoingIntent(context)).setup()
        try {
            notifier.showUnlessDisplayed(result.copy(key = key.copy(accountId = AccountId("other"))))
            notifier.showUnlessDisplayed(result.copy(key = key.copy(callId = "other-call")))
            assertEquals(2, replyNotifications())
        } finally { activity.pause().stop().destroy() }
    }

    private fun replyNotifications() = manager.activeNotifications.count { it.id == CallReplyNotifier.NotificationId }
}
