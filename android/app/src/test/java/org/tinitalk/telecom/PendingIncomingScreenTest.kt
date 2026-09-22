package org.tinitalk.telecom

import android.app.Application
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.tinitalk.CallActivity
import org.tinitalk.call.CallAdmission
import org.tinitalk.call.CallAdmissionHandoff
import org.tinitalk.call.CallSessionBinding
import org.tinitalk.data.AccountId
import org.tinitalk.push.IncomingCallScreenState
import org.tinitalk.push.IncomingInvite
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35], application = org.tinitalk.i18n.LocalizedTestApplication::class)
class PendingIncomingScreenTest {
    private val context = RuntimeEnvironment.getApplication()
    private val admission = CallAdmissionHandoff(CallAdmission())
    private val incoming = IncomingCallController(admission = admission)
    private val invite = IncomingInvite(
        AccountId("account"), CallSessionBinding("https://talk.example", "alice", "session", "config"),
        "incoming", "Bob", Instant.now().plusSeconds(60),
    )

    @After fun cleanup() {
        incoming.clear(context, invite.owner)
        IncomingCallScreenState.hidden()
    }

    @Test fun pendingCallOpensTheIncomingScreen() {
        incoming.admitIncoming(context, invite)
        assertTrue(incoming.openPendingScreen(context))
        val launched = shadowOf(context).nextStartedActivity
        assertEquals(CallActivity::class.java.name, launched.component?.className)
        assertEquals(IncomingCallController.ActionIncoming, launched.action)
        assertEquals(invite, IncomingCallController.inviteFrom(launched))
    }

    @Test fun returningFromTheCallScreenDoesNotImmediatelyReopenIt() {
        incoming.admitIncoming(context, invite)
        // MainActivity resumes before the departing CallActivity receives onStop.
        IncomingCallScreenState.shown(invite.owner)
        assertDoesNotOpen()
        IncomingCallScreenState.hidden(invite.owner)
        // Opening the app later must still bring back an unanswered call.
        assertTrue(incoming.openPendingScreen(context))
    }

    @Test fun answeringOrRejectingCallDoesNotReopenRinging() {
        incoming.admitIncoming(context, invite)
        for (action in listOf(IncomingCallController.ActionAnswer, IncomingCallController.ActionReject)) {
            incoming.save(context, invite, action)
            assertDoesNotOpen()
        }
    }

    @Test fun runningCallDoesNotReopenRingingEvenIfPendingDataRemains() {
        incoming.admitIncoming(context, invite)
        val lease = requireNotNull(admission.take(invite.owner))
        try {
            assertDoesNotOpen()
        } finally {
            admission.release(lease)
        }
    }

    @Test fun canceledCallDoesNotReopen() {
        incoming.admitIncoming(context, invite)
        incoming.rememberTerminal(context, invite.owner)
        assertDoesNotOpen()
    }

    @Test fun expiredCallDoesNotReopen() {
        incoming.admitIncoming(context, invite)
        incoming.save(context, invite.copy(expiresAt = Instant.now().minusSeconds(1)))
        assertDoesNotOpen()
        assertNull(incoming.load(context))
    }

    @Test fun noPendingCallDoesNotNavigate() = assertDoesNotOpen()

    private fun assertDoesNotOpen() {
        assertFalse(incoming.openPendingScreen(context))
        assertNull(shadowOf(context).nextStartedActivity)
    }
}
