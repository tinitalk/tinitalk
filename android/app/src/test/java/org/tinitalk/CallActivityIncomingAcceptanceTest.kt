package org.tinitalk

import android.os.Looper
import org.tinitalk.call.CallDirection
import org.tinitalk.call.CallPeer
import org.tinitalk.call.CallPhase
import org.tinitalk.call.CallUiStateStore
import org.tinitalk.push.IncomingInvite
import org.tinitalk.telecom.IncomingCallController
import java.time.Instant
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CallActivityIncomingAcceptanceTest {
    @Test
    fun acceptedIncomingCallStaysOpenAfterRingingPresentationFinishes() {
        val context = RuntimeEnvironment.getApplication()
        val incoming = IncomingCallController()
        val invite = IncomingInvite(
            callId = "accepted-call",
            caller = "Alice",
            expiresAt = Instant.now().plusSeconds(30),
        )
        incoming.save(context, invite)
        val activityIntent = Shadows.shadowOf(
            incoming.activityIntent(context, IncomingCallController.ActionIncoming, invite),
        ).savedIntent
        val activity = Robolectric.buildActivity(CallActivity::class.java, activityIntent)
            .create()
            .start()
            .resume()

        CallUiStateStore.begin(
            invite.callId,
            CallPeer(invite.caller),
            CallDirection.Incoming,
            CallPhase.Active,
        )
        incoming.finishTerminalPresentation(context, invite.callId) {}
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(600, TimeUnit.MILLISECONDS)

        assertFalse(activity.get().isFinishing)
        activity.pause().stop().destroy()
        CallUiStateStore.reset()
    }
}
