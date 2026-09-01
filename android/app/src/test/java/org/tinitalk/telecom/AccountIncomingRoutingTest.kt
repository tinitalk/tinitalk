package org.tinitalk.telecom

import org.tinitalk.call.AccountCallKey
import org.tinitalk.call.CallAdmission
import org.tinitalk.call.CallAdmissionHandoff
import org.tinitalk.call.CallSessionBinding
import org.tinitalk.data.AccountId
import org.tinitalk.push.IncomingInvite
import java.time.Instant
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AccountIncomingRoutingTest {
    @Test
    fun accountAndSessionBindingSurvivePersistenceAndNotificationActionIntent() {
        val context = RuntimeEnvironment.getApplication()
        val admission = CallAdmissionHandoff(CallAdmission())
        val controller = IncomingCallController(admission = admission)
        val invite = invite("account-b", "same-call")

        assertEquals(IncomingAdmissionResult.Admitted, controller.admitIncoming(context, invite))
        var presented = false
        assertTrue(controller.presentSavedIncoming(context, invite) { presented = true })
        assertTrue(presented)
        assertEquals(invite, controller.load(context)?.invite)

        val pending = controller.actionIntent(context, IncomingCallController.ActionAnswer, invite)
        assertEquals(invite, IncomingCallController.inviteFrom(Shadows.shadowOf(pending).savedIntent))

        controller.finishTerminalPresentation(context, invite.owner) {}
    }

    @Test
    fun persistedOwnerIsReclaimedBeforeAnotherAccountCanOverwriteIt() {
        val context = RuntimeEnvironment.getApplication()
        val admission = CallAdmissionHandoff(CallAdmission())
        val firstController = IncomingCallController(admission = admission)
        val inviteA = invite("account-a", "same-call")
        val inviteB = invite("account-b", "same-call")
        var presentations = 0

        firstController.save(context, inviteA)
        val restoredController = IncomingCallController(admission = admission)

        assertEquals(
            IncomingAdmissionResult.Busy,
            restoredController.admitIncoming(context, inviteB),
        )
        assertEquals(0, presentations)
        assertEquals(inviteA, restoredController.load(context)?.invite)

        restoredController.finishTerminalPresentation(context, inviteA.owner) {}
    }

    @Test
    fun removedPersistedOwnerIsDiscardedBeforeAValidInviteIsAdmitted() {
        val context = RuntimeEnvironment.getApplication()
        val admission = CallAdmissionHandoff(CallAdmission())
        val controller = IncomingCallController(admission = admission)
        val removed = invite("account-a", "removed-call")
        val current = invite("account-b", "current-call")
        controller.save(context, removed)

        assertEquals(null, controller.reclaimPending(context, isCurrent = { false }))
        assertTrue(controller.isTerminal(context, removed.owner))
        assertEquals(IncomingAdmissionResult.Admitted, controller.admitIncoming(context, current))

        controller.finishTerminalPresentation(context, current.owner) {}
    }

    @Test
    fun expiredPendingReservationIsPrunedBeforeTheNextInvite() {
        val context = RuntimeEnvironment.getApplication()
        val admission = CallAdmissionHandoff(CallAdmission())
        val controller = IncomingCallController(admission = admission)
        val inviteA = invite("account-a", "call-a", expiresAt = Instant.parse("2026-08-31T10:00:01Z"))
        val inviteB = invite("account-b", "call-b", expiresAt = Instant.parse("2026-08-31T10:01:00Z"))

        assertEquals(
            IncomingAdmissionResult.Admitted,
            controller.admitIncoming(context, inviteA, now = Instant.parse("2026-08-31T10:00:00Z")),
        )
        assertTrue(controller.expirePending(context, inviteA.owner, now = Instant.parse("2026-08-31T10:00:02Z")))
        assertEquals(null, controller.load(context))
        assertEquals(
            IncomingAdmissionResult.Admitted,
            controller.admitIncoming(context, inviteB, now = Instant.parse("2026-08-31T10:00:02Z")),
        )

        controller.finishTerminalPresentation(context, inviteB.owner) {}
    }

    @Test
    fun equalRawCallIdsRemainDistinctForPendingIntentsAndTombstones() {
        val context = RuntimeEnvironment.getApplication()
        val controller = IncomingCallController(admission = CallAdmissionHandoff(CallAdmission()))
        val inviteA = invite("account-a", "same-call")
        val inviteB = invite("account-b", "same-call")
        val actionA = controller.actionIntent(context, IncomingCallController.ActionReject, inviteA)
        val actionB = controller.actionIntent(context, IncomingCallController.ActionReject, inviteB)

        assertNotEquals(actionA, actionB)

        val remembered = TerminalCallTombstones.remember(emptySet(), inviteA.owner, 1_000)
        assertTrue(TerminalCallTombstones.contains(remembered, inviteA.owner, 1_001))
        assertEquals(false, TerminalCallTombstones.contains(remembered, inviteB.owner, 1_001))
    }

    @Test
    fun pendingIntentsDoNotAliasAcrossReplacementSessions() {
        val context = RuntimeEnvironment.getApplication()
        val controller = IncomingCallController(admission = CallAdmissionHandoff(CallAdmission()))
        val current = invite("account-a", "same-call")
        val replacement = current.copy(
            sessionBinding = current.sessionBinding.copy(sessionId = "replacement-session"),
        )

        assertNotEquals(
            controller.actionIntent(context, IncomingCallController.ActionReject, current),
            controller.actionIntent(context, IncomingCallController.ActionReject, replacement),
        )
        assertNotEquals(
            controller.activityIntent(context, IncomingCallController.ActionAnswer, current),
            controller.activityIntent(context, IncomingCallController.ActionAnswer, replacement),
        )
    }

    @Test
    fun staleOtherAccountTerminalCannotCancelTheCurrentPresentation() {
        val context = RuntimeEnvironment.getApplication()
        val controller = IncomingCallController(admission = CallAdmissionHandoff(CallAdmission()))
        val inviteA = invite("account-a", "same-call")
        val inviteB = invite("account-b", "same-call")
        var cancelled = false
        assertEquals(IncomingAdmissionResult.Admitted, controller.admitIncoming(context, inviteA))

        assertEquals(false, controller.finishTerminalPresentation(context, inviteB.owner) { cancelled = true })
        assertEquals(false, cancelled)
        assertEquals(inviteA, controller.load(context)?.invite)

        controller.finishTerminalPresentation(context, inviteA.owner) {}
    }

    @Test
    fun staleSessionTerminalCannotCancelReplacementWithTheSameAccountAndCallId() {
        val context = RuntimeEnvironment.getApplication()
        val controller = IncomingCallController(admission = CallAdmissionHandoff(CallAdmission()))
        val current = invite("account-a", "same-call")
        val stale = current.copy(
            sessionBinding = current.sessionBinding.copy(sessionId = "previous-session"),
        )
        var cancelled = false
        assertEquals(IncomingAdmissionResult.Admitted, controller.admitIncoming(context, current))

        assertEquals(false, controller.rememberTerminalIfCompatible(context, stale.owner))
        assertEquals(false, controller.finishTerminalPresentation(context, stale.owner) { cancelled = true })
        assertEquals(false, cancelled)
        assertEquals(current, controller.load(context)?.invite)
        assertEquals(false, controller.isTerminal(context, current.owner))

        controller.finishTerminalPresentation(context, current.owner) {}
    }

    @Test
    fun terminalFromPreviousSessionDoesNotRejectReplacementWithTheSameAccountAndCallId() {
        val context = RuntimeEnvironment.getApplication()
        val controller = IncomingCallController(admission = CallAdmissionHandoff(CallAdmission()))
        val previous = invite("account-a", "same-call")
        val replacement = previous.copy(
            sessionBinding = previous.sessionBinding.copy(sessionId = "replacement-session"),
        )
        assertEquals(IncomingAdmissionResult.Admitted, controller.admitIncoming(context, previous))
        assertTrue(controller.finishTerminalPresentation(context, previous.owner) {})

        assertEquals(IncomingAdmissionResult.Admitted, controller.admitIncoming(context, replacement))

        controller.finishTerminalPresentation(context, replacement.owner) {}
    }

    @Test
    fun staleSessionBusyRejectionDoesNotTombstoneTheCurrentSameKeyInvite() {
        val context = RuntimeEnvironment.getApplication()
        val admission = CallAdmissionHandoff(CallAdmission())
        val controller = IncomingCallController(admission = admission)
        val current = invite("account-a", "same-call")
        val stale = current.copy(
            sessionBinding = current.sessionBinding.copy(sessionId = "previous-session"),
        )
        assertEquals(IncomingAdmissionResult.Admitted, controller.admitIncoming(context, current))
        assertEquals(IncomingAdmissionResult.Busy, controller.admitIncoming(context, stale))

        assertEquals(false, controller.isTerminal(context, current.owner))
        assertEquals(IncomingAdmissionResult.Invalid, controller.admitIncoming(context, stale))
        assertTrue(controller.presentSavedIncoming(context, current) {})

        controller.finishTerminalPresentation(context, current.owner) {}
    }

    @Test
    fun terminalServiceHandoffRetainsReservationUntilTheServiceTakesIt() {
        val context = RuntimeEnvironment.getApplication()
        val admission = CallAdmissionHandoff(CallAdmission())
        val controller = IncomingCallController(admission = admission)
        val invite = invite("account-a", "call-a")
        assertEquals(IncomingAdmissionResult.Admitted, controller.admitIncoming(context, invite))

        assertTrue(
            controller.handoffTerminalPresentation(context, invite.owner) {},
        )

        assertEquals(null, controller.load(context))
        val lease = admission.take(invite.owner)
        assertNotNull(lease)
        admission.release(requireNotNull(lease))
    }

    @Test
    fun terminalTeardownCompletesBeforeAReplacementCanPresent() {
        val context = RuntimeEnvironment.getApplication()
        val controller = IncomingCallController(admission = CallAdmissionHandoff(CallAdmission()))
        val inviteA = invite("account-a", "call-a")
        val inviteB = invite("account-b", "call-b")
        val cancelEntered = CountDownLatch(1)
        val allowCancel = CountDownLatch(1)
        val replacementStarted = CountDownLatch(1)
        val replacementFinished = CountDownLatch(1)
        val order = Collections.synchronizedList(mutableListOf<String>())
        assertEquals(IncomingAdmissionResult.Admitted, controller.admitIncoming(context, inviteA))

        val teardown = Thread {
            controller.finishTerminalPresentation(context, inviteA.owner) {
                order += "cancel-start"
                cancelEntered.countDown()
                allowCancel.await(2, TimeUnit.SECONDS)
                order += "cancel-end"
            }
        }.apply { start() }
        assertTrue(cancelEntered.await(2, TimeUnit.SECONDS))
        val replacement = Thread {
            replacementStarted.countDown()
            assertEquals(IncomingAdmissionResult.Admitted, controller.admitIncoming(context, inviteB))
            order += "admit"
            assertTrue(controller.presentSavedIncoming(context, inviteB) { order += "present" })
            replacementFinished.countDown()
        }.apply { start() }
        assertTrue(replacementStarted.await(2, TimeUnit.SECONDS))
        assertEquals(false, replacementFinished.await(100, TimeUnit.MILLISECONDS))

        allowCancel.countDown()
        teardown.join(2_000)
        replacement.join(2_000)

        assertTrue(replacementFinished.await(2, TimeUnit.SECONDS))
        assertEquals(listOf("cancel-start", "cancel-end", "admit", "present"), order)
        controller.finishTerminalPresentation(context, inviteB.owner) {}
    }

    private fun invite(
        account: String,
        callId: String,
        expiresAt: Instant = Instant.now().plusSeconds(30),
    ): IncomingInvite {
        val accountId = AccountId(account)
        return IncomingInvite(
            accountId = accountId,
            sessionBinding = CallSessionBinding("https://$account.example", account, "session-$account", "config-$account"),
            callId = callId,
            caller = "Caller",
            expiresAt = expiresAt,
        )
    }
}
