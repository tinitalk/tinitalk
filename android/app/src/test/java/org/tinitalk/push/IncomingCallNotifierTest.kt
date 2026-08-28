package org.tinitalk.push

import org.tinitalk.CallActivity
import org.tinitalk.telecom.IncomingAnswerClaim
import org.tinitalk.telecom.IncomingCallController
import java.time.Instant
import org.junit.Assert.assertEquals
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
}
