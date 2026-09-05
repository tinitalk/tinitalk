package org.tinitalk.telecom

import android.os.Looper
import org.tinitalk.call.CallAudioState
import org.tinitalk.call.AccountCallKey
import org.tinitalk.call.AccountCallOwner
import org.tinitalk.call.CallAdmissionAttempt
import org.tinitalk.call.CallSessionBinding
import org.tinitalk.call.GlobalCallAdmission
import org.tinitalk.call.CallCoordinator
import org.tinitalk.call.CallDirection
import org.tinitalk.call.CallPhase
import org.tinitalk.call.CallPeer
import org.tinitalk.call.CallSnapshot
import org.tinitalk.call.CallUiStateStore
import org.tinitalk.call.SignalClient
import org.tinitalk.data.ContactAddress
import org.tinitalk.data.signal.SignalEvent
import org.tinitalk.data.AccountId
import org.tinitalk.push.IncomingInvite
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CallForegroundServiceTelecomCallbackTest {
    @Test
    fun answeringIncomingCallKeepsPeerAddressAfterPresentationCleanup() {
        CallUiStateStore.reset()
        val controller = Robolectric.buildService(CallForegroundService::class.java).create()
        val service = controller.get()
        val accountId = AccountId("account-a")
        val binding = CallSessionBinding("https://a.example", "alice", "session-a", "config-a")
        val invite = IncomingInvite(
            accountId = accountId,
            sessionBinding = binding,
            callId = "incoming-call",
            caller = "Bob",
            expiresAt = Instant.parse("2099-01-01T00:00:00Z"),
            callerLogin = "bob",
        )
        val expectedAddress = ContactAddress.of(binding.serverUrl, "bob")
        val expectedPeer = CallPeer("Bob", "bob", expectedAddress)
        val attempt = GlobalCallAdmission.stage(invite.owner)
        assertTrue(attempt is CallAdmissionAttempt.Acquired)
        val lease = requireNotNull(GlobalCallAdmission.take(invite.owner))
        val coordinator = CallCoordinator("alice", NoopSignalClient(), accountId = accountId)
        val incomingController = IncomingCallController()
        incomingController.save(service, invite)
        service.setPrivateField("coordinator", coordinator)
        service.setPrivateField("telecomCallKey", invite.key)
        service.setPrivateField("callOwner", invite.owner)
        service.setPrivateField("admissionLease", lease)
        val answerIntent = Shadows.shadowOf(
            incomingController.activityIntent(service, IncomingCallController.ActionAnswer, invite),
        ).savedIntent
            .setClass(service, CallForegroundService::class.java)
            .setAction(CallForegroundService.ActionAnswer)

        try {
            service.onStartCommand(answerIntent, 0, 1)

            val state = CallUiStateStore.snapshot()
            assertEquals(CallPhase.Active, state.phase)
            assertEquals(invite.key, state.callKey)
            assertEquals(CallDirection.Incoming, state.direction)
            assertEquals(expectedPeer, state.peer)
        } finally {
            controller.destroy()
            GlobalCallAdmission.release(lease)
            CallUiStateStore.reset()
        }
    }

    @Test
    fun acceptsLocalTelecomCallbackAfterCrossedCallAdoptsCanonicalId() {
        val controller = Robolectric.buildService(CallForegroundService::class.java).create()
        val service = controller.get()
        val accountId = AccountId("account-a")
        val localKey = AccountCallKey(accountId, LocalTelecomCallId)
        val canonicalKey = AccountCallKey(accountId, CanonicalCallId)
        val owner = AccountCallOwner(
            canonicalKey,
            CallSessionBinding("https://a.example", "alice", "session-a", "config-a"),
        )
        val acquired = GlobalCallAdmission.stage(owner) as CallAdmissionAttempt.Acquired
        val lease = requireNotNull(GlobalCallAdmission.take(owner))
        val coordinator = CallCoordinator("alice", NoopSignalClient(), accountId = accountId).apply {
            restoreIncoming(CanonicalCallId, acknowledgeRinging = false)
            accept()
        }
        service.setPrivateField("coordinator", coordinator)
        service.setPrivateField("telecomCallKey", localKey)
        service.setPrivateField("callOwner", owner)
        service.setPrivateField("admissionLease", lease)
        CallUiStateStore.begin(
            canonicalKey,
            CallPeer("Bob"),
            CallDirection.Outgoing,
            CallPhase.Active,
        )
        val endpoint = AudioEndpoint("speaker", "Speaker", 4)
        val endpoints = AudioEndpointState(endpoint, listOf(endpoint))
        var disconnected = false

        val callbacks = service.telecomCallbacksForTest(localKey) { disconnected = true }
        callbacks.onEndpointsChanged(endpoints)
        callbacks.onDisconnect()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals(CallSnapshot(CallPhase.Active, CanonicalCallId, 0, accountId), coordinator.snapshot())
        assertEquals(endpoints, CallAudioState.snapshot())
        assertEquals(endpoint, CallUiStateStore.snapshot().currentAudioEndpoint)
        assertTrue(disconnected)
        controller.destroy()
    }

    private fun CallForegroundService.setPrivateField(name: String, value: Any) {
        javaClass.getDeclaredField(name).apply {
            isAccessible = true
            set(this@setPrivateField, value)
        }
    }

    private fun CallForegroundService.telecomCallbacksForTest(
        callKey: AccountCallKey,
        onDisconnect: () -> Unit,
    ): TelecomCallCallbacks {
        val method = javaClass.getDeclaredMethod(
            "telecomCallbacks",
            AccountCallKey::class.java,
            Function0::class.java,
        ).apply { isAccessible = true }
        return method.invoke(this, callKey, onDisconnect) as TelecomCallCallbacks
    }

    private class NoopSignalClient : SignalClient {
        override fun send(event: SignalEvent, onSettled: (() -> Unit)?) = Unit
    }

    private companion object {
        const val LocalTelecomCallId = "local-call"
        const val CanonicalCallId = "canonical-call"
    }
}
