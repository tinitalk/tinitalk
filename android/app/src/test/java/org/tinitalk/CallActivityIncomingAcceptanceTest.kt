package org.tinitalk

import android.os.Looper
import org.tinitalk.call.CallDirection
import org.tinitalk.call.CallSessionBinding
import org.tinitalk.call.CallPeer
import org.tinitalk.call.CallPhase
import org.tinitalk.call.CallUiStateStore
import org.tinitalk.push.IncomingInvite
import org.tinitalk.data.AccountId
import org.tinitalk.data.AccountPeerKey
import org.tinitalk.data.AuthStore
import org.tinitalk.data.MemoryKeyValueStore
import org.tinitalk.data.PrefixTokenCipher
import org.tinitalk.data.Session
import org.tinitalk.telecom.IncomingCallController
import java.time.Instant
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun staleSessionRedialNeitherAcknowledgesHistoryNorStartsACall() {
        val auth = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher()) { AccountId("account-a") }
        val previous = auth.upsert(
            Session(
                "https://account.example",
                "alice",
                "old-token",
                sessionId = "old-session",
                configId = "config-a",
            ),
        )
        val binding = CallSessionBinding.from(previous.session)
        val peer = AccountPeerKey(previous.id, "bob")
        auth.upsert(previous.session.copy(token = "new-token", sessionId = "new-session"))
        var acknowledged = false
        var started = false

        val accepted = executePinnedRedial(
            auth,
            peer,
            binding,
            acknowledge = { acknowledged = true },
            start = { started = true },
        )

        assertFalse(accepted)
        assertFalse(acknowledged)
        assertFalse(started)
    }

    @Test
    fun currentSessionRedialAcknowledgesHistoryAndStartsACall() {
        val auth = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher()) { AccountId("account-a") }
        val current = auth.upsert(
            Session(
                "https://account.example",
                "alice",
                "token",
                sessionId = "current-session",
                configId = "config-a",
            ),
        )
        val binding = CallSessionBinding.from(current.session)
        val peer = AccountPeerKey(current.id, "bob")
        var acknowledged = false
        var started = false

        val accepted = executePinnedRedial(
            auth,
            peer,
            binding,
            acknowledge = { acknowledged = true },
            start = { started = true },
        )

        assertTrue(accepted)
        assertTrue(acknowledged)
        assertTrue(started)
    }

    @Test
    fun acceptedIncomingCallStaysOpenAfterRingingPresentationFinishes() {
        val context = RuntimeEnvironment.getApplication()
        val incoming = IncomingCallController()
        val invite = IncomingInvite(
            accountId = AccountId("account-a"),
            sessionBinding = CallSessionBinding("https://a.example", "alice", "session-a", "config-a"),
            callId = "accepted-call",
            caller = "Alice",
            expiresAt = Instant.now().plusSeconds(30),
        )
        incoming.admitIncoming(context, invite)
        val activityIntent = Shadows.shadowOf(
            incoming.activityIntent(context, IncomingCallController.ActionIncoming, invite),
        ).savedIntent
        val activity = Robolectric.buildActivity(CallActivity::class.java, activityIntent)
            .create()
            .start()
            .resume()

        CallUiStateStore.begin(
            invite.key,
            CallPeer(invite.caller),
            CallDirection.Incoming,
            CallPhase.Active,
        )
        incoming.finishTerminalPresentation(context, invite.owner) {}
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(600, TimeUnit.MILLISECONDS)

        assertFalse(activity.get().isFinishing)
        activity.pause().stop().destroy()
        CallUiStateStore.reset()
    }
}
