package org.tinitalk.push

import android.app.NotificationManager
import org.tinitalk.CallActivity
import org.tinitalk.telecom.IncomingAnswerClaim
import org.tinitalk.telecom.IncomingCallController
import org.tinitalk.telecom.TelecomCallCallbacks
import org.tinitalk.telecom.TelecomCallController
import org.tinitalk.telecom.TelecomCapabilities
import org.tinitalk.telecom.TelecomRegistrar
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class IncomingCallNotifierTest {
    @Test
    fun fullScreenIntentAndAlertingChannelMatchTheSelectedPresentation() {
        val context = RuntimeEnvironment.getApplication()
        val notifier = IncomingCallNotifier(context)
        val invite = IncomingInvite(
            callId = "call-presentation",
            caller = "Alice",
            expiresAt = Instant.now().plusSeconds(30),
        )

        val locked = notifier.buildIncomingNotification(invite, IncomingCallPresentationMode.FullScreen)!!
        val headsUp = notifier.buildIncomingNotification(invite, IncomingCallPresentationMode.HeadsUp)!!
        val inApp = notifier.buildIncomingNotification(invite, IncomingCallPresentationMode.InApp)!!
        val manager = context.getSystemService(NotificationManager::class.java)

        assertNotNull(locked.fullScreenIntent)
        assertNull(headsUp.fullScreenIntent)
        assertNull(inApp.fullScreenIntent)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, manager.getNotificationChannel(headsUp.channelId).importance)
        assertEquals(NotificationManager.IMPORTANCE_LOW, manager.getNotificationChannel(inApp.channelId).importance)
        notifier.cancel()
    }

    @Test
    fun appAcceptsIncomingCallWithoutWaitingForTelecom() {
        val context = RuntimeEnvironment.getApplication()
        val controller = IncomingCallController {
            TelecomCallController(SilentAnswerTelecomRegistrar())
        }
        val invite = IncomingInvite(
            callId = "call-without-telecom",
            caller = "Alice",
            expiresAt = Instant.now().plusSeconds(30),
        )
        controller.save(context, invite)

        controller.answer(context, invite)

        assertEquals(IncomingCallController.ActionAnswer, controller.load(context)?.action)
    }

    @Suppress("DEPRECATION")
    @Test
    fun answerActionOpensCallScreenDirectly() {
        val context = RuntimeEnvironment.getApplication()
        val invite = IncomingInvite(
            callId = "call-1",
            caller = "Alice",
            expiresAt = Instant.now().plusSeconds(30),
        )

        val notification = IncomingCallNotifier(context).buildIncomingNotification(invite)!!
        val answer = notification.actions
            .map { it.actionIntent }
            .first { Shadows.shadowOf(it).savedIntent.action == IncomingCallController.ActionAnswer }
        val shadowAnswer = Shadows.shadowOf(answer)
        val answerIntent = shadowAnswer.savedIntent

        assertTrue(shadowAnswer.isActivityIntent)
        assertEquals(CallActivity::class.java.name, answerIntent.component?.className)
        assertEquals(IncomingCallController.ActionAnswer, answerIntent.action)
    }

    @Test
    fun answerActionCanOnlyBeClaimedOnce() {
        val context = RuntimeEnvironment.getApplication()
        val controller = IncomingCallController()
        val invite = IncomingInvite(
            callId = "call-once",
            caller = "Alice",
            expiresAt = Instant.now().plusSeconds(30),
        )
        controller.save(context, invite)

        assertEquals(IncomingAnswerClaim.Claimed, controller.claimAnswer(context, invite))
        assertEquals(IncomingAnswerClaim.AlreadyClaimed, controller.claimAnswer(context, invite))

        val expired = invite.copy(callId = "call-expired", expiresAt = Instant.now().minusSeconds(1))
        controller.save(context, expired)
        assertEquals(IncomingAnswerClaim.Invalid, controller.claimAnswer(context, expired))
    }

    @Test
    fun repeatedPresentationDoesNotLoseClaimedAnswer() {
        val context = RuntimeEnvironment.getApplication()
        val controller = IncomingCallController()
        val invite = IncomingInvite(
            callId = "call-claimed",
            caller = "Alice",
            expiresAt = Instant.now().plusSeconds(30),
        )
        controller.save(context, invite)
        assertEquals(IncomingAnswerClaim.Claimed, controller.claimAnswer(context, invite))

        assertTrue(controller.presentIncoming(context, invite) {})

        assertEquals(IncomingAnswerClaim.AlreadyClaimed, controller.claimAnswer(context, invite))
    }

    @Test
    fun systemDisconnectImmediatelyPreventsIncomingCallReplay() {
        val context = RuntimeEnvironment.getApplication()
        val controller = IncomingCallController()
        val invite = IncomingInvite(
            callId = "call-rejected-by-system",
            caller = "Alice",
            expiresAt = Instant.now().plusSeconds(30),
        )
        controller.save(context, invite)

        controller.disconnectFromTelecom(context, invite)

        assertTrue(controller.isTerminal(context, invite.callId))
        assertEquals(null, controller.load(context))
        assertEquals(null, IncomingCallNotifier(context).buildIncomingNotification(invite))
    }

    @Test
    fun staleTerminalEventDoesNotDismissANewerIncomingCall() {
        val context = RuntimeEnvironment.getApplication()
        val controller = IncomingCallController()
        val current = IncomingInvite(
            callId = "new-call",
            caller = "Bob",
            expiresAt = Instant.now().plusSeconds(30),
        )
        controller.save(context, current)
        var cancelled = false

        val finished = controller.finishTerminalPresentation(context, "old-call") {
            cancelled = true
        }

        assertEquals(false, finished)
        assertEquals(false, cancelled)
        assertEquals(current.callId, controller.load(context)?.invite?.callId)
    }

    private class SilentAnswerTelecomRegistrar : TelecomRegistrar {
        override fun register(capabilities: TelecomCapabilities) = Unit
        override fun addIncoming(invite: IncomingInvite, callbacks: TelecomCallCallbacks) = Unit
        override fun addOutgoing(callId: String, displayName: String, callbacks: TelecomCallCallbacks) = Unit
        override fun answer(callId: String, onResult: (Boolean) -> Unit) = Unit
        override fun reject(callId: String) = Unit
        override fun setActive(callId: String, onResult: (Boolean) -> Unit) = onResult(false)
        override fun selectEndpoint(callId: String, endpointId: String) = Unit
        override fun cancel(callId: String) = Unit
    }
}
