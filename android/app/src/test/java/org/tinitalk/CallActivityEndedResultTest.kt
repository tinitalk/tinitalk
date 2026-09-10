package org.tinitalk

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import java.time.Instant
import java.time.Duration
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSystemClock
import org.tinitalk.call.*
import org.tinitalk.data.*
import org.tinitalk.media.MediaConnectionState
import org.tinitalk.push.IncomingInvite
import org.tinitalk.telecom.IncomingCallController

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w384dp-h853dp-mdpi")
class CallActivityEndedResultTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = RuntimeEnvironment.getApplication()
    private val key = AccountCallKey(AccountId("ended-account"), "ended-call")
    private val peer = CallPeer("Bob", "bob", ContactAddress.of("https://ended.example", "bob"))
    private val incoming = IncomingCallController()
    private val session = Session("https://ended.example", "alice", "token", sessionId = "session", configId = "config")
    private val auth = AuthStore(SharedPreferencesKeyValueStore(context), PrefixTokenCipher()) { key.accountId }
    private val invite = IncomingInvite(
        accountId = key.accountId,
        sessionBinding = CallSessionBinding("https://ended.example", "alice", "session", "config"),
        callId = key.callId,
        caller = "Bob",
        callerLogin = "bob",
        expiresAt = Instant.now().plusSeconds(30),
    )

    @Before fun seedAccount() { auth.upsert(session) }

    @After fun cleanup() {
        incoming.finishTerminalPresentation(context, invite.owner) {}
        CallReplyResultStore(context).clear()
        CallUiStateStore.reset()
        CallServiceState.reset()
        auth.remove(key.accountId)
        AuthSessionEvents.clear()
    }

    @Test fun outgoingLocalHangupKeepsFinalDuration() = checkConversation(CallDirection.Outgoing, CallEndReason.LocalHangup)
    @Test fun outgoingRemoteHangupKeepsFinalDuration() = checkConversation(CallDirection.Outgoing, CallEndReason.RemoteHangup)
    @Test fun incomingLocalHangupKeepsFinalDuration() = checkConversation(CallDirection.Incoming, CallEndReason.LocalHangup)
    @Test fun incomingRemoteHangupKeepsFinalDuration() = checkConversation(CallDirection.Incoming, CallEndReason.RemoteHangup)

    @Test fun outgoingScreenSwitchesToWaitingWhenPeerReportsRinging() {
        CallUiStateStore.begin(key, peer, CallDirection.Outgoing, CallPhase.Connecting)
        val activity = Robolectric.buildActivity(CallActivity::class.java, outgoingIntent()).setup()
        try {
            compose.onNodeWithText("Пробуем связаться…").assertIsDisplayed()
            compose.runOnIdle {
                CallUiStateStore.sync(CallSnapshot(CallPhase.Ringing, key.callId, 1, key.accountId))
            }
            compose.onNodeWithText("Ждём ответа…").assertIsDisplayed()
            compose.onNodeWithText("Пробуем связаться…").assertDoesNotExist()
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun outgoingTimeoutShowsTheRightResultFromBothWaitingStages() {
        for (phase in listOf(CallPhase.Connecting, CallPhase.Ringing)) {
            CallUiStateStore.begin(key, peer, CallDirection.Outgoing, CallPhase.Connecting)
            val activity = Robolectric.buildActivity(CallActivity::class.java, outgoingIntent()).setup()
            try {
                compose.onNodeWithText("Пробуем связаться…").assertIsDisplayed()
                if (phase == CallPhase.Ringing) {
                    compose.runOnIdle {
                        CallUiStateStore.sync(CallSnapshot(CallPhase.Ringing, key.callId, 1, key.accountId))
                    }
                    compose.onNodeWithText("Ждём ответа…").assertIsDisplayed()
                }
                compose.runOnIdle {
                    CallUiStateStore.sync(CallSnapshot(CallPhase.Ended, key.callId, 2, key.accountId), CallEndReason.TimedOut)
                }
                val title = if (phase == CallPhase.Connecting) "Не удалось связаться" else "Нет ответа"
                compose.onNodeWithText(title).assertIsDisplayed()
                compose.onNodeWithText("Пробуем связаться…").assertDoesNotExist()
                compose.onNodeWithText("Ждём ответа…").assertDoesNotExist()
                compose.onNodeWithText("00:00").assertDoesNotExist()
                CallUiStateStore.reset(key)
                advanceTimeBy(2_000)
                assertFalse("$phase closed early", activity.get().isFinishing)
                compose.onNodeWithText(title).assertIsDisplayed()
                advanceTimeBy(1_200)
                compose.waitForIdle()
                assertTrue("$phase did not close", activity.get().isFinishing)
            } finally { activity.pause().stop().destroy() }
        }
    }

    private fun checkConversation(direction: CallDirection, reason: CallEndReason) {
        val activity = startConversation(direction)
        try {
            val timerBounds = compose.onNodeWithText("01:05").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            endConversation(reason, direction)
            compose.onNodeWithText("Звонок завершён").assertIsDisplayed()
            assertEquals(timerBounds, compose.onNodeWithText("01:05").assertIsDisplayed().fetchSemanticsNode().boundsInRoot)
            advanceTimeBy(1_000)
            CallUiStateStore.reset(key)
            advanceTimeBy(1_000)
            assertFalse(activity.get().isFinishing)
            compose.onNodeWithText("Звонок завершён").assertIsDisplayed()
            compose.onNodeWithText("01:05").assertIsDisplayed()
            advanceTimeBy(1_200)
            compose.waitForIdle()
            assertTrue(activity.get().isFinishing)
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun incomingResultSurvivesCleanupAndRecreation() {
        val activity = startConversation(CallDirection.Incoming)
        val originalIntent = Intent(activity.get().intent)
        endConversation(CallEndReason.RemoteHangup, CallDirection.Incoming)
        CallUiStateStore.reset(key)
        val saved = Bundle()
        activity.pause().saveInstanceState(saved).stop().destroy()
        val recreated = Robolectric.buildActivity(CallActivity::class.java, originalIntent).create(saved).start().resume().visible()
        try {
            compose.onNodeWithText("Звонок завершён").assertIsDisplayed()
            compose.onNodeWithText("01:05").assertIsDisplayed()
            advanceTimeBy(2_000)
            assertFalse(recreated.get().isFinishing)
            advanceTimeBy(1_200)
            compose.waitForIdle()
            assertTrue(recreated.get().isFinishing)
        } finally { recreated.pause().stop().destroy() }
    }

    @Test fun incomingResultDoesNotReappearAfterHoursLocked() = checkResultAfterLock(CallDirection.Incoming)
    @Test fun outgoingResultDoesNotReappearAfterHoursLocked() = checkResultAfterLock(CallDirection.Outgoing)

    private fun checkResultAfterLock(direction: CallDirection) {
        val activity = startConversation(direction)
        try {
            endConversation(CallEndReason.LocalHangup, direction)
            activity.pause().stop()
            CallUiStateStore.reset(key)
            ShadowSystemClock.simulateDeepSleep(Duration.ofHours(2))
            activity.start().resume()
            assertTrue("Expired result must close before the screen becomes visible", activity.get().isFinishing)
            activity.visible()
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun completedCallInBackgroundDoesNotReappearAfterHoursLocked() {
        val activity = startConversation(CallDirection.Incoming)
        try {
            activity.pause().stop()
            endConversation(CallEndReason.RemoteHangup, CallDirection.Incoming)
            CallUiStateStore.reset(key)
            ShadowSystemClock.simulateDeepSleep(Duration.ofHours(2))
            activity.start().resume()
            assertTrue(activity.get().isFinishing)
            activity.visible()
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun expiredResultCannotBeRestoredAfterProcessRecreation() {
        val activity = startConversation(CallDirection.Incoming)
        val originalIntent = Intent(activity.get().intent)
        endConversation(CallEndReason.RemoteHangup, CallDirection.Incoming)
        CallUiStateStore.reset(key)
        val saved = Bundle()
        activity.pause().saveInstanceState(saved).stop().destroy()
        ShadowSystemClock.simulateDeepSleep(Duration.ofHours(2))
        val recreated = Robolectric.buildActivity(CallActivity::class.java, originalIntent).create(saved).start()
        try {
            assertTrue("Saved result must retain its original deadline", recreated.get().isFinishing)
            recreated.resume().visible()
        } finally { recreated.pause().stop().destroy() }
    }

    @Test fun briefBackgroundVisitDoesNotRestartEndedTimer() {
        val activity = startConversation(CallDirection.Incoming)
        try {
            endConversation(CallEndReason.RemoteHangup, CallDirection.Incoming)
            advanceTimeBy(1_000)
            activity.pause().stop()
            ShadowSystemClock.simulateDeepSleep(Duration.ofMillis(500))
            activity.start().resume().visible()
            advanceTimeBy(500)
            assertFalse(activity.get().isFinishing)
            advanceTimeBy(1_200)
            compose.waitForIdle()
            assertTrue(activity.get().isFinishing)
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun endedScreenClosesWhileActivityIsStopped() {
        val activity = startConversation(CallDirection.Outgoing)
        try {
            endConversation(CallEndReason.RemoteHangup, CallDirection.Outgoing)
            activity.pause().stop()
            advanceTimeBy(3_200)
            compose.waitForIdle()
            assertTrue(activity.get().isFinishing)
        } finally { activity.destroy() }
    }

    @Test fun nextIncomingAfterDeepSleepSurvivesStartBeforeNewIntent() {
        val activity = startConversation(CallDirection.Outgoing)
        var next = invite.copy(callId = "next-after-sleep")
        try {
            endConversation(CallEndReason.LocalHangup, CallDirection.Outgoing)
            activity.pause().stop()
            CallUiStateStore.reset(key)
            ShadowSystemClock.simulateDeepSleep(Duration.ofHours(2))
            next = next.copy(expiresAt = Instant.now().plusSeconds(30))
            incoming.admitIncoming(context, next)
            assertEquals(CallPhase.Idle, CallUiStateStore.snapshot().phase)

            // Android can start a stopped singleTop Activity before delivering its queued intent.
            activity.start()
            assertFalse("The previous result must not close a pending new call", activity.get().isFinishing)
            // Avoid the capability request's Android Keystore when applying the incoming intent.
            auth.remove(key.accountId)
            val nextIntent = Shadows.shadowOf(
                incoming.activityIntent(context, IncomingCallController.ActionIncoming, next),
            ).savedIntent
            activity.newIntent(nextIntent).resume().visible()
            compose.onNodeWithText("Входящий звонок").assertIsDisplayed()
            compose.runOnIdle { auth.upsert(session) }
            advanceTimeBy(4_000)
            assertFalse(activity.get().isFinishing)
            compose.onNodeWithText("Входящий звонок").assertIsDisplayed()
        } finally {
            incoming.finishTerminalPresentation(context, next.owner) {}
            activity.pause().stop().destroy()
        }
    }

    @Test fun nextCallReplacesResultWithoutBeingClosedByOldTimer() {
        val activity = startConversation(CallDirection.Outgoing)
        try {
            endConversation(CallEndReason.LocalHangup, CallDirection.Outgoing)
            CallUiStateStore.reset(key)
            advanceTimeBy(2_000)
            val next = AccountCallKey(key.accountId, "next-call")
            CallUiStateStore.begin(next, peer, CallDirection.Outgoing, CallPhase.Ringing)
            activity.newIntent(outgoingIntent(next))
            compose.onNodeWithText("Звонок завершён").assertDoesNotExist()
            compose.onNodeWithText("01:05").assertDoesNotExist()
            advanceTimeBy(6_000)
            assertFalse(activity.get().isFinishing)
            assertEquals(next, CallUiStateStore.snapshot().callKey)
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun removedSessionClosesItsEndedScreen() {
        val activity = startConversation(CallDirection.Incoming)
        try {
            endConversation(CallEndReason.RemoteHangup, CallDirection.Incoming)
            CallUiStateStore.reset(key)
            compose.runOnIdle {
                auth.remove(key.accountId)
                AuthSessionEvents.publish(AuthSessionEvent(key.accountId, session))
            }
            assertTrue(activity.get().isFinishing)
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun removedSessionCannotRestoreAnEndedScreenAfterProcessStateWasSaved() {
        val activity = startConversation(CallDirection.Incoming)
        val intent = Intent(activity.get().intent)
        endConversation(CallEndReason.RemoteHangup, CallDirection.Incoming)
        CallUiStateStore.reset(key)
        val saved = Bundle()
        activity.pause().saveInstanceState(saved).stop().destroy()
        auth.remove(key.accountId)
        AuthSessionEvents.clear()
        val recreated = Robolectric.buildActivity(CallActivity::class.java, intent).create(saved).start().resume().visible()
        try {
            assertTrue(recreated.get().isFinishing)
        } finally { recreated.pause().stop().destroy() }
    }

    @Test fun decliningIncomingCallKeepsEndedScreenAfterRingingStateIsCleared() {
        // This path does not need the reply-capability request, whose token requires Android Keystore.
        auth.remove(key.accountId)
        incoming.admitIncoming(context, invite)
        CallUiStateStore.begin(key, peer, CallDirection.Incoming, CallPhase.Ringing)
        val intent = Shadows.shadowOf(incoming.activityIntent(context, IncomingCallController.ActionIncoming, invite)).savedIntent
        val activity = Robolectric.buildActivity(CallActivity::class.java, intent).setup()
        try {
            compose.onNodeWithText("Входящий звонок").assertIsDisplayed()
            compose.runOnIdle {
                auth.upsert(session)
                incoming.reject(context, invite)
            }
            compose.onNodeWithText("Звонок завершён").assertIsDisplayed()
            compose.onNodeWithText("00:00").assertDoesNotExist()
            advanceTimeBy(2_000)
            assertFalse(activity.get().isFinishing)
            advanceTimeBy(1_200)
            compose.waitForIdle()
            assertTrue(activity.get().isFinishing)
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun callerCancellationKeepsUnacceptedIncomingResult() = checkUnacceptedIncomingResult(expired = false)
    @Test fun timeoutKeepsUnacceptedIncomingResult() = checkUnacceptedIncomingResult(expired = true)

    private fun checkUnacceptedIncomingResult(expired: Boolean) {
        val activity = startUnacceptedIncoming()
        try {
            compose.runOnIdle {
                if (expired) assertTrue(incoming.expirePending(context, invite.owner, invite.expiresAt))
                else incoming.finishTerminalPresentation(context, invite.owner) {}
            }
            ShadowLooper.idleMainLooper(600, TimeUnit.MILLISECONDS)
            assertIncomingResult(activity)
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun incomingCancelledInBackgroundShowsResultOnReturn() {
        val activity = startUnacceptedIncoming()
        try {
            activity.pause().stop()
            incoming.finishTerminalPresentation(context, invite.owner) {}
            ShadowLooper.idleMainLooper(5, TimeUnit.SECONDS)
            activity.start().resume().visible()
            assertIncomingResult(activity)
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun unacceptedIncomingResultSurvivesRecreation() {
        val activity = startUnacceptedIncoming()
        val originalIntent = Intent(activity.get().intent)
        compose.runOnIdle { incoming.finishTerminalPresentation(context, invite.owner) {} }
        ShadowLooper.idleMainLooper(600, TimeUnit.MILLISECONDS)
        val saved = Bundle()
        activity.pause().saveInstanceState(saved).stop().destroy()
        val recreated = Robolectric.buildActivity(CallActivity::class.java, originalIntent).create(saved).start().resume().visible()
        try { assertIncomingResult(recreated) } finally { recreated.pause().stop().destroy() }
    }

    @Test fun incomingCancelledBeforeMonitorSurvivesRecreation() =
        checkPendingIncomingRestoration(cancelBeforeSave = true)

    @Test fun incomingCancelledAfterSaveSurvivesRecreation() = checkPendingIncomingRestoration()

    @Test fun incomingExpiredAfterSaveSurvivesRecreation() = checkPendingIncomingRestoration(expired = true)

    @Test fun incomingExpiredHoursAgoDoesNotCreateANewEndedScreen() {
        val activity = startUnacceptedIncoming()
        val saved = Bundle()
        activity.pause().saveInstanceState(saved).stop().destroy()
        val oldIntent = Shadows.shadowOf(incoming.activityIntent(
            context, IncomingCallController.ActionIncoming,
            invite.copy(expiresAt = Instant.now().minusSeconds(7_200)),
        )).savedIntent
        val recreated = Robolectric.buildActivity(CallActivity::class.java, oldIntent).create(saved).start()
        try {
            assertTrue("An old invitation must not get a fresh ended timestamp", recreated.get().isFinishing)
            recreated.resume().visible()
        } finally { recreated.pause().stop().destroy() }
    }

    private fun checkPendingIncomingRestoration(cancelBeforeSave: Boolean = false, expired: Boolean = false) {
        val activity = startUnacceptedIncoming()
        var originalIntent = Intent(activity.get().intent)
        if (cancelBeforeSave) incoming.finishTerminalPresentation(context, invite.owner) {}
        val saved = Bundle()
        // No monitor tick: only the displayed Ringing state can be saved here.
        activity.pause().saveInstanceState(saved).stop().destroy()
        if (expired) {
            // Restore the same call after its deadline, without a terminal push or runtime event.
            originalIntent = Shadows.shadowOf(incoming.activityIntent(
                context, IncomingCallController.ActionIncoming,
                invite.copy(expiresAt = Instant.now().minusSeconds(1)),
            )).savedIntent
            assertFalse(incoming.isTerminal(context, invite.owner))
        } else if (!cancelBeforeSave) {
            incoming.finishTerminalPresentation(context, invite.owner) {}
        }
        val recreated = Robolectric.buildActivity(CallActivity::class.java, originalIntent).create(saved).start().resume().visible()
        try { assertIncomingResult(recreated) } finally { recreated.pause().stop().destroy() }
    }

    @Test fun removedSessionDoesNotRetainUnacceptedIncomingResult() {
        checkInvalidatedIncomingSession(replaced = false)
    }

    @Test fun replacedSessionDoesNotRetainUnacceptedIncomingResult() {
        checkInvalidatedIncomingSession(replaced = true)
    }

    private fun checkInvalidatedIncomingSession(replaced: Boolean) {
        val activity = startUnacceptedIncoming()
        try {
            activity.pause().stop()
            incoming.finishTerminalPresentation(context, invite.owner) {}
            if (replaced) auth.upsert(session.copy(sessionId = "replacement-session"))
            else auth.remove(key.accountId)
            activity.start().resume().visible()
            assertTrue(activity.get().isFinishing)
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun nextCallReplacesUnacceptedIncomingResult() {
        val activity = startUnacceptedIncoming()
        try {
            compose.runOnIdle { incoming.finishTerminalPresentation(context, invite.owner) {} }
            ShadowLooper.idleMainLooper(600, TimeUnit.MILLISECONDS)
            compose.onNodeWithText("Звонок завершён").assertIsDisplayed()
            val next = AccountCallKey(key.accountId, "next-call")
            CallUiStateStore.begin(next, peer, CallDirection.Outgoing, CallPhase.Ringing)
            activity.newIntent(outgoingIntent(next))
            compose.onNodeWithText("Ждём ответа…").assertIsDisplayed()
            advanceTimeBy(4_000)
            ShadowLooper.idleMainLooper(600, TimeUnit.MILLISECONDS)
            assertFalse(activity.get().isFinishing)
            assertEquals(next, CallUiStateStore.snapshot().callKey)
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun nextIncomingCallGetsItsOwnEndedResult() {
        val activity = startUnacceptedIncoming()
        val next = invite.copy(callId = "next-incoming")
        try {
            compose.runOnIdle { incoming.finishTerminalPresentation(context, invite.owner) {} }
            ShadowLooper.idleMainLooper(600, TimeUnit.MILLISECONDS)
            compose.onNodeWithText("Звонок завершён").assertIsDisplayed()
            compose.runOnIdle {
                auth.remove(key.accountId)
                incoming.admitIncoming(context, next)
            }
            val nextIntent = Shadows.shadowOf(
                incoming.activityIntent(context, IncomingCallController.ActionIncoming, next),
            ).savedIntent
            activity.newIntent(nextIntent)
            compose.onNodeWithText("Входящий звонок").assertIsDisplayed()
            compose.runOnIdle { auth.upsert(session) }
            advanceTimeBy(4_000)
            assertFalse(activity.get().isFinishing)
            compose.runOnIdle { incoming.finishTerminalPresentation(context, next.owner) {} }
            ShadowLooper.idleMainLooper(600, TimeUnit.MILLISECONDS)
            assertIncomingResult(activity)
        } finally {
            incoming.finishTerminalPresentation(context, next.owner) {}
            activity.pause().stop().destroy()
        }
    }

    private fun startUnacceptedIncoming(): ActivityController<CallActivity> {
        // Ordinary incoming presentation has no shared Ringing state or call runtime yet.
        // Avoid the capability request's Android Keystore while mounting the screen.
        auth.remove(key.accountId)
        incoming.admitIncoming(context, invite)
        val intent = Shadows.shadowOf(incoming.activityIntent(context, IncomingCallController.ActionIncoming, invite)).savedIntent
        return Robolectric.buildActivity(CallActivity::class.java, intent).setup().also {
            compose.onNodeWithText("Входящий звонок").assertIsDisplayed()
            assertEquals(CallPhase.Idle, CallUiStateStore.snapshot().phase)
            compose.runOnIdle { auth.upsert(session) }
        }
    }

    private fun assertIncomingResult(activity: ActivityController<CallActivity>) {
        compose.waitForIdle()
        assertFalse("Incoming result closed before its reading time", activity.get().isFinishing)
        compose.onNodeWithText("Звонок завершён").assertIsDisplayed()
        compose.onNodeWithText("Bob").assertIsDisplayed()
        compose.onNodeWithText("00:00").assertDoesNotExist()
        assertEquals("Presentation must not create a call runtime", CallPhase.Idle, CallUiStateStore.snapshot().phase)
        advanceTimeBy(2_000)
        assertFalse(activity.get().isFinishing)
        advanceTimeBy(1_200)
        compose.waitForIdle()
        assertTrue(activity.get().isFinishing)
    }

    @Test fun unansweredOutcomesStayThreeSecondsWithoutConversationDuration() {
        for (reason in listOf(CallEndReason.Busy, CallEndReason.NotInContacts, CallEndReason.TimedOut,
            CallEndReason.Cancelled, CallEndReason.ConnectionLost, CallEndReason.Failed)) {
            CallUiStateStore.begin(key, peer, CallDirection.Outgoing, CallPhase.Ringing)
            CallUiStateStore.sync(CallSnapshot(CallPhase.Ended, key.callId, 1, key.accountId), reason)
            val activity = Robolectric.buildActivity(CallActivity::class.java, outgoingIntent()).setup()
            try {
                val title = when (reason) {
                    CallEndReason.Busy -> "Занято"
                    CallEndReason.NotInContacts, CallEndReason.Failed, CallEndReason.ConnectionLost -> "Не удалось связаться"
                    CallEndReason.TimedOut -> "Нет ответа"
                    else -> "Звонок завершён"
                }
                compose.onNodeWithText(title).assertIsDisplayed()
                compose.onNodeWithText("00:00").assertDoesNotExist()
                CallUiStateStore.reset(key)
                advanceTimeBy(2_000)
                assertFalse("$reason closed early", activity.get().isFinishing)
                compose.onNodeWithText(title).assertIsDisplayed()
                advanceTimeBy(1_200)
                compose.waitForIdle()
                assertTrue("$reason did not close", activity.get().isFinishing)
            } finally { activity.pause().stop().destroy() }
        }
    }

    private fun startConversation(direction: CallDirection): ActivityController<CallActivity> {
        if (direction == CallDirection.Incoming) incoming.admitIncoming(context, invite)
        CallUiStateStore.begin(key, peer, direction, CallPhase.Active)
        CallUiStateStore.onMediaConnection(MediaConnectionState.Connected)
        SystemClock.sleep(65_000)
        val intent = if (direction == CallDirection.Incoming) {
            Shadows.shadowOf(incoming.activityIntent(context, IncomingCallController.ActionIncoming, invite)).savedIntent
        } else outgoingIntent()
        return Robolectric.buildActivity(CallActivity::class.java, intent).setup().also {
            compose.onNodeWithText("01:05").assertIsDisplayed()
        }
    }

    private fun endConversation(reason: CallEndReason, direction: CallDirection) {
        compose.runOnIdle {
            CallUiStateStore.sync(CallSnapshot(CallPhase.Ended, key.callId, 1, key.accountId), reason)
            if (direction == CallDirection.Incoming) incoming.finishTerminalPresentation(context, invite.owner) {}
        }
    }

    private fun advanceTimeBy(millis: Long) {
        ShadowLooper.idleMainLooper(millis, TimeUnit.MILLISECONDS)
        compose.waitForIdle()
    }

    private fun outgoingIntent(callKey: AccountCallKey = key): Intent = CallActivity.outgoingIntent(
        context, AccountPeerKey(callKey.accountId, "bob"), requireNotNull(peer.contactAddress), "Bob", callKey,
    )
}
