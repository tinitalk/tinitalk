package org.tinitalk

import android.content.Intent
import android.os.Bundle
import android.os.Looper
import java.time.Duration
import java.util.concurrent.TimeUnit
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemClock
import org.tinitalk.call.*
import org.tinitalk.data.AccountId
import org.tinitalk.data.AccountPeerKey
import org.tinitalk.data.ContactAddress

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class CallActivityReplyResultTest {
    @get:Rule val composeRule = createEmptyComposeRule()
    private val context = RuntimeEnvironment.getApplication()
    private val key = AccountCallKey(AccountId("reply-account"), "reply-call")
    private val peer = CallPeer("Bob", "bob", ContactAddress.of("https://reply.example", "bob"))

    private fun advanceTimeBy(millis: Long) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(millis, TimeUnit.MILLISECONDS)
        composeRule.waitForIdle()
    }

    @After fun cleanup() {
        CallReplyResultStore(context).clear()
        CallUiStateStore.reset()
        CallServiceState.reset()
    }

    @Test
    fun resultSurvivesServiceResetAndActivityRecreationThenClosesAutomatically() {
        CallReplyResultStore(context).save(CallReplyResult(key, peer, CallReplyCode.CallMeLater))
        val intent = CallActivity.outgoingIntent(context, AccountPeerKey(key.accountId, "bob"), requireNotNull(peer.contactAddress), "Bob", key)
        val activity = Robolectric.buildActivity(CallActivity::class.java, intent).setup()
        composeRule.onNodeWithText("просит перезвонить позже").assertExists()
        CallUiStateStore.reset()
        advanceTimeBy(2_000)
        assertFalse(activity.get().isFinishing)
        val saved = Bundle()
        activity.pause().saveInstanceState(saved).stop().destroy()
        val recreated = Robolectric.buildActivity(CallActivity::class.java, intent).create(saved).start().resume().visible()
        composeRule.onNodeWithText("просит перезвонить позже").assertExists()
        advanceTimeBy(500)
        assertFalse(recreated.get().isFinishing)
        advanceTimeBy(700)
        composeRule.waitForIdle()
        assertTrue(recreated.get().isFinishing)
        assertNull(CallReplyResultStore(context).load())
        recreated.pause().stop().destroy()
    }

    @Test fun replyDoesNotReappearAfterHoursLocked() {
        CallReplyResultStore(context).save(CallReplyResult(key, peer, CallReplyCode.CannotTalk))
        val intent = CallActivity.outgoingIntent(context, AccountPeerKey(key.accountId, "bob"), requireNotNull(peer.contactAddress), "Bob", key)
        val activity = Robolectric.buildActivity(CallActivity::class.java, intent).setup()
        try {
            composeRule.onNodeWithText("сейчас не может говорить").assertExists()
            activity.pause().stop()
            ShadowSystemClock.simulateDeepSleep(Duration.ofHours(2))
            activity.start().resume()
            assertTrue(activity.get().isFinishing)
            assertNull(CallReplyResultStore(context).load())
            activity.visible()
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun backCanCloseReplyBeforeTimeout() {
        CallReplyResultStore(context).save(CallReplyResult(key, peer, CallReplyCode.WillCallBack))
        val intent = CallActivity.outgoingIntent(context, AccountPeerKey(key.accountId, "bob"), requireNotNull(peer.contactAddress), "Bob", key)
        val activity = Robolectric.buildActivity(CallActivity::class.java, intent).setup()
        try {
            composeRule.onNodeWithText("обещает перезвонить позже").assertExists()
            composeRule.runOnIdle { activity.get().onBackPressedDispatcher.onBackPressed() }
            assertTrue(activity.get().isFinishing)
            assertNull(CallReplyResultStore(context).load())
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun oldStoredReplyDoesNotOpenAFreshEndedScreen() {
        CallReplyResultStore(context).save(CallReplyResult(key, peer, CallReplyCode.CannotTalk))
        ShadowSystemClock.simulateDeepSleep(Duration.ofHours(2))
        val intent = CallActivity.outgoingIntent(context, AccountPeerKey(key.accountId, "bob"), requireNotNull(peer.contactAddress), "Bob", key)
        val activity = Robolectric.buildActivity(CallActivity::class.java, intent).create().start()
        try {
            assertTrue(activity.get().isFinishing)
            assertNull(CallReplyResultStore(context).load())
            activity.resume().visible()
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun replyTimeoutCannotCloseANewCallInTheSameActivity() {
        CallReplyResultStore(context).save(CallReplyResult(key, peer, CallReplyCode.CannotTalk))
        val intent = CallActivity.outgoingIntent(context, AccountPeerKey(key.accountId, "bob"), requireNotNull(peer.contactAddress), "Bob", key)
        val activity = Robolectric.buildActivity(CallActivity::class.java, intent).setup()
        try {
            composeRule.onNodeWithText("сейчас не может говорить").assertExists()
            advanceTimeBy(2_000)
            val next = AccountCallKey(key.accountId, "next-call")
            CallUiStateStore.begin(next, peer, CallDirection.Outgoing, CallPhase.Ringing)
            activity.newIntent(CallActivity.outgoingIntent(context, AccountPeerKey(next.accountId, "bob"), requireNotNull(peer.contactAddress), "Bob", next))
            composeRule.onNodeWithText("сейчас не может говорить").assertDoesNotExist()
            advanceTimeBy(6_000)
            composeRule.waitForIdle()
            assertFalse(activity.get().isFinishing)
            assertEquals(next, CallUiStateStore.snapshot().callKey)
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun rejectedCallWithoutReplyStaysForThreeSeconds() {
        CallUiStateStore.begin(key, peer, CallDirection.Outgoing, CallPhase.Ringing)
        CallUiStateStore.sync(CallSnapshot(CallPhase.Ended, key.callId, 1, key.accountId), CallEndReason.Rejected)
        val intent = CallActivity.outgoingIntent(context, AccountPeerKey(key.accountId, "bob"), requireNotNull(peer.contactAddress), "Bob", key)
        val activity = Robolectric.buildActivity(CallActivity::class.java, intent).setup()
        try {
            composeRule.onNodeWithText("Звонок отклонён").assertExists()
            advanceTimeBy(2_000)
            assertFalse(activity.get().isFinishing)
            advanceTimeBy(1_200)
            composeRule.waitForIdle()
            assertTrue(activity.get().isFinishing)
        } finally { activity.pause().stop().destroy() }
    }

    @Test
    fun reopenedOngoingCallShowsReplyAfterServiceResetAndRecreation() {
        CallUiStateStore.begin(key, peer, CallDirection.Outgoing, CallPhase.Ringing)
        val intent = CallActivity.ongoingIntent(context)
        val originalIntent = Intent(intent)
        val activity = Robolectric.buildActivity(CallActivity::class.java, intent).setup()
        CallReplyResultStore(context).save(CallReplyResult(key, peer, CallReplyCode.CallMeLater))
        CallUiStateStore.sync(CallSnapshot(CallPhase.Ended, key.callId, 1, key.accountId), CallEndReason.Rejected)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        composeRule.onNodeWithText("просит перезвонить позже").assertExists()
        CallUiStateStore.reset()
        val saved = Bundle()
        activity.pause().saveInstanceState(saved).stop().destroy()
        val recreated = Robolectric.buildActivity(CallActivity::class.java, originalIntent).create(saved).start().resume().visible()
        try {
            advanceTimeBy(2_000)
            assertFalse(recreated.get().isFinishing)
            composeRule.onNodeWithText("просит перезвонить позже").assertExists()
        } finally { recreated.pause().stop().destroy() }
    }

    @Test
    fun newOutgoingCallCannotBeCoveredByPreviousReply() {
        CallReplyResultStore(context).save(CallReplyResult(key, peer, CallReplyCode.CannotTalk))
        val next = AccountCallKey(AccountId("another-account"), "next-call")
        val intent = CallActivity.outgoingIntent(context, AccountPeerKey(next.accountId, "bob"), requireNotNull(peer.contactAddress), "Bob", next)
        val activity = Robolectric.buildActivity(CallActivity::class.java, intent).setup()
        composeRule.onNodeWithText("сейчас не может говорить").assertDoesNotExist()
        assertNull(CallReplyResultStore(context).load())
        activity.pause().stop().destroy()
    }
}
