package org.tinitalk.push

import android.app.Notification
import android.app.NotificationManager
import android.app.Person
import org.tinitalk.CallActivity
import org.tinitalk.data.CallUnreadState
import org.tinitalk.data.AccountId
import org.tinitalk.data.Session
import org.tinitalk.call.AccountCallKey
import org.tinitalk.call.CallSessionBinding
import org.tinitalk.call.CallAdmission
import org.tinitalk.call.CallAdmissionHandoff
import org.tinitalk.data.UnreadMissedContact
import org.tinitalk.telecom.IncomingAnswerClaim
import org.tinitalk.telecom.IncomingCallController
import org.tinitalk.telecom.TelecomCallCallbacks
import org.tinitalk.telecom.TelecomCallController
import org.tinitalk.telecom.TelecomCapabilities
import org.tinitalk.telecom.TelecomRegistrar
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
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
    private val accountId = AccountId("account-a")
    private fun invite(callId: String, caller: String = "Alice") = IncomingInvite(
        accountId,
        CallSessionBinding("https://a.example", "alice", "session-a", "config-a"),
        callId,
        caller,
        Instant.now().plusSeconds(30),
    )
    private fun controller() = IncomingCallController(CallAdmissionHandoff(CallAdmission()))

    @Test
    fun incomingPushRequiresExactSessionTarget() {
        val session = Session("https://a.example", "alice", "token", sessionId = "session-a")

        assertEquals(false, IncomingPushPayload.matchesTarget(mapOf("type" to "incoming_call"), session, "phone"))
        assertEquals(
            true,
            IncomingPushPayload.matchesTarget(
                mapOf(
                    "target_login" to "alice",
                    "target_device_id" to "phone",
                    "target_session_id" to "session-a",
                ),
                session,
                "phone",
            ),
        )
    }

    @Test
    fun serverMissedStateWithoutAnExactSessionOmitsRedial() {
        val context = RuntimeEnvironment.getApplication()
        val notifier = IncomingCallNotifier(context)
        notifier.syncMissedAccounts(listOf(accountId))
        val refreshId = notifier.beginAccountMissedCountRefresh(accountId)

        notifier.updateAccountMissedState(
            accountId,
            CallUnreadState(
                unreadMissedCount = 2,
                unreadMissed = listOf(
                    UnreadMissedContact("anna", 200),
                    UnreadMissedContact("ira", 100),
                ),
            ),
            refreshId,
            immediate = true,
        )

        val manager = context.getSystemService(NotificationManager::class.java)
        val notification = manager.activeNotifications
            .single { it.notification.category == Notification.CATEGORY_MISSED_CALL }
            .notification
        assertTrue(notification.actions.isNullOrEmpty())
    }

    @Test
    fun historyBackedCurrentSessionOffersPinnedRedial() {
        val context = RuntimeEnvironment.getApplication()
        val notifier = IncomingCallNotifier(context)
        val binding = CallSessionBinding("https://a.example", "alice", "session-a", "config-a")
        notifier.syncMissedAccounts(listOf(accountId))

        notifier.updateAccountMissedState(
            accountId,
            CallUnreadState(1, listOf(UnreadMissedContact("anna", 200))),
            notifier.beginAccountMissedCountRefresh(accountId),
            redialBinding = binding,
            immediate = true,
        )

        val notification = context.getSystemService(NotificationManager::class.java)
            .activeNotifications
            .single { it.notification.category == Notification.CATEGORY_MISSED_CALL }
            .notification
        val redialIntent = Shadows.shadowOf(notification.actions.single().actionIntent).savedIntent

        assertEquals("anna", redialIntent.getStringExtra("outgoing_login"))
        assertEquals(binding.serverUrl, redialIntent.getStringExtra("redial_server_url"))
        assertEquals(binding.sessionId, redialIntent.getStringExtra("redial_session_id"))
        assertTrue(redialIntent.data.toString().contains("session-a"))
    }

    @Test
    fun inviteBackedMissedRedialCarriesItsSessionBinding() {
        val context = RuntimeEnvironment.getApplication()
        val notifier = IncomingCallNotifier(context)
        val missed = invite("missed-call").copy(callerLogin = "anna")
        notifier.syncMissedAccounts(listOf(accountId))
        val refreshId = notifier.beginAccountMissedCountRefresh(accountId)

        notifier.updateAccountMissedState(
            accountId,
            CallUnreadState(1, listOf(UnreadMissedContact("anna", 200))),
            refreshId,
            latest = missed,
            immediate = true,
        )

        val notification = context.getSystemService(NotificationManager::class.java)
            .activeNotifications
            .single { it.notification.category == Notification.CATEGORY_MISSED_CALL }
            .notification
        val firstRedial = Shadows.shadowOf(notification.actions.single().actionIntent)
        val redialIntent = firstRedial.savedIntent

        assertEquals(missed.sessionBinding.serverUrl, redialIntent.getStringExtra("redial_server_url"))
        assertEquals(missed.sessionBinding.login, redialIntent.getStringExtra("redial_session_login"))
        assertEquals(missed.sessionBinding.sessionId, redialIntent.getStringExtra("redial_session_id"))
        assertEquals(missed.sessionBinding.configId, redialIntent.getStringExtra("redial_config_id"))
        assertTrue(redialIntent.data.toString().contains("session-a"))

        val replacement = missed.copy(
            sessionBinding = missed.sessionBinding.copy(sessionId = "replacement-session"),
        )
        notifier.updateAccountMissedState(
            accountId,
            CallUnreadState(1, listOf(UnreadMissedContact("anna", 200))),
            notifier.beginAccountMissedCountRefresh(accountId),
            latest = replacement,
            immediate = true,
        )
        val replacementRedial = Shadows.shadowOf(
            context.getSystemService(NotificationManager::class.java)
                .activeNotifications
                .single { it.notification.category == Notification.CATEGORY_MISSED_CALL }
                .notification.actions.single().actionIntent,
        )

        assertNotEquals(firstRedial.requestCode, replacementRedial.requestCode)
        assertEquals("replacement-session", replacementRedial.savedIntent.getStringExtra("redial_session_id"))
    }

    @Test
    fun fullScreenIntentAndAlertingChannelMatchTheSelectedPresentation() {
        val context = RuntimeEnvironment.getApplication()
        val notifier = IncomingCallNotifier(context)
        val invite = invite("call-presentation")
        val incoming = IncomingCallController()
        incoming.admitIncoming(context, invite)

        val locked = notifier.buildIncomingNotification(invite, IncomingCallPresentationMode.FullScreen)!!
        val headsUp = notifier.buildIncomingNotification(invite, IncomingCallPresentationMode.HeadsUp)!!
        val inApp = notifier.buildIncomingNotification(invite, IncomingCallPresentationMode.InApp)!!
        val manager = context.getSystemService(NotificationManager::class.java)

        assertNotNull(locked.fullScreenIntent)
        assertNull(headsUp.fullScreenIntent)
        assertNull(inApp.fullScreenIntent)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, manager.getNotificationChannel(headsUp.channelId).importance)
        assertEquals(NotificationManager.IMPORTANCE_LOW, manager.getNotificationChannel(inApp.channelId).importance)
        incoming.finishTerminalPresentation(context, invite.owner) {}
        notifier.cancel()
    }

    @Test
    fun callStyleShowsCallerWithIncomingStatusText() {
        val context = RuntimeEnvironment.getApplication()
        val invite = invite("call-text")
        val incoming = IncomingCallController()
        incoming.admitIncoming(context, invite)

        val notification = IncomingCallNotifier(context).buildIncomingNotification(invite)!!
        val caller = notification.extras.getParcelable(Notification.EXTRA_CALL_PERSON, Person::class.java)

        assertEquals("Alice", caller?.name)
        assertEquals("Входящий звонок", notification.extras.getCharSequence(Notification.EXTRA_TEXT))
        incoming.finishTerminalPresentation(context, invite.owner) {}
    }

    @Test
    fun appAcceptsIncomingCallWithoutWaitingForTelecom() {
        val context = RuntimeEnvironment.getApplication()
        val controller = IncomingCallController(
            { TelecomCallController(SilentAnswerTelecomRegistrar()) },
            CallAdmissionHandoff(CallAdmission()),
        )
        val invite = invite("call-without-telecom")
        controller.admitIncoming(context, invite)

        controller.answer(context, invite)

        assertEquals(IncomingCallController.ActionAnswer, controller.load(context)?.action)
    }

    @Suppress("DEPRECATION")
    @Test
    fun answerActionOpensCallScreenDirectly() {
        val context = RuntimeEnvironment.getApplication()
        val invite = invite("call-1")
        val incoming = IncomingCallController()
        incoming.admitIncoming(context, invite)

        val notification = IncomingCallNotifier(context).buildIncomingNotification(invite)!!
        val answer = notification.actions
            .map { it.actionIntent }
            .first { Shadows.shadowOf(it).savedIntent.action == IncomingCallController.ActionAnswer }
        val shadowAnswer = Shadows.shadowOf(answer)
        val answerIntent = shadowAnswer.savedIntent

        assertTrue(shadowAnswer.isActivityIntent)
        assertEquals(CallActivity::class.java.name, answerIntent.component?.className)
        assertEquals(IncomingCallController.ActionAnswer, answerIntent.action)
        incoming.finishTerminalPresentation(context, invite.owner) {}
    }

    @Test
    fun answerActionCanOnlyBeClaimedOnce() {
        val context = RuntimeEnvironment.getApplication()
        val controller = controller()
        val invite = invite("call-once")
        controller.admitIncoming(context, invite)

        assertEquals(IncomingAnswerClaim.Claimed, controller.claimAnswer(context, invite))
        assertEquals(IncomingAnswerClaim.AlreadyClaimed, controller.claimAnswer(context, invite))

        val expired = invite.copy(callId = "call-expired", expiresAt = Instant.now().minusSeconds(1))
        controller.finishTerminalPresentation(context, invite.owner) {}
        controller.save(context, expired)
        assertEquals(IncomingAnswerClaim.Invalid, controller.claimAnswer(context, expired))
    }

    @Test
    fun repeatedPresentationDoesNotLoseClaimedAnswer() {
        val context = RuntimeEnvironment.getApplication()
        val controller = controller()
        val invite = invite("call-claimed")
        controller.admitIncoming(context, invite)
        assertEquals(IncomingAnswerClaim.Claimed, controller.claimAnswer(context, invite))

        assertTrue(controller.presentSavedIncoming(context, invite) {})

        assertEquals(IncomingAnswerClaim.AlreadyClaimed, controller.claimAnswer(context, invite))
    }

    @Test
    fun systemDisconnectImmediatelyPreventsIncomingCallReplay() {
        val context = RuntimeEnvironment.getApplication()
        val controller = controller()
        val invite = invite("call-rejected-by-system")
        controller.admitIncoming(context, invite)

        controller.disconnectFromTelecom(context, invite)

        assertTrue(controller.isTerminal(context, invite.owner))
        assertEquals(null, controller.load(context))
        assertEquals(null, IncomingCallNotifier(context).buildIncomingNotification(invite))
    }

    @Test
    fun staleTerminalEventDoesNotDismissANewerIncomingCall() {
        val context = RuntimeEnvironment.getApplication()
        val controller = controller()
        val current = invite("new-call", "Bob")
        controller.admitIncoming(context, current)
        var cancelled = false

        val finished = controller.finishTerminalPresentation(
            context,
            invite("old-call").owner,
        ) {
            cancelled = true
        }

        assertEquals(false, finished)
        assertEquals(false, cancelled)
        assertEquals(current.callId, controller.load(context)?.invite?.callId)
    }

    private class SilentAnswerTelecomRegistrar : TelecomRegistrar {
        override fun register(capabilities: TelecomCapabilities) = Unit
        override fun addIncoming(invite: IncomingInvite, callbacks: TelecomCallCallbacks) = Unit
        override fun addOutgoing(key: AccountCallKey, displayName: String, callbacks: TelecomCallCallbacks) = Unit
        override fun answer(key: AccountCallKey, onResult: (Boolean) -> Unit) = Unit
        override fun reject(key: AccountCallKey) = Unit
        override fun setActive(key: AccountCallKey, onResult: (Boolean) -> Unit) = onResult(false)
        override fun selectEndpoint(key: AccountCallKey, endpointId: String) = Unit
        override fun cancel(key: AccountCallKey) = Unit
    }
}
