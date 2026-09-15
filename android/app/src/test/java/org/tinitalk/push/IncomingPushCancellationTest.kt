package org.tinitalk.push

import android.app.Application
import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.tinitalk.call.*
import org.tinitalk.data.AccountId
import org.tinitalk.data.AccountRecord
import org.tinitalk.data.Session
import org.tinitalk.telecom.CallForegroundService
import org.tinitalk.telecom.IncomingCallController
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class IncomingPushCancellationTest {
    private val context = RuntimeEnvironment.getApplication()
    private val account = AccountRecord(AccountId("push-end-account"),
        Session("https://tone.example", "alice", "token", sessionId = "session"))
    private val key = AccountCallKey(account.id, "push-end-call")
    private val owner = AccountCallOwner(key, CallSessionBinding.from(account.session))
    private val disconnected = mutableListOf<AccountCallKey>()
    private var lease: CallAdmissionLease? = null

    @After fun cleanup() {
        lease?.let(GlobalCallAdmission::release)
        CallServiceState.reset()
        CallUiStateStore.reset()
    }

    @Test fun endPushBeforeSignalingLeavesAudioTeardownToCallService() {
        running(CallPhase.Active)
        IncomingPushHandler(context, disconnected::add).handle(account, payload("call.end"))

        assertEquals(CallForegroundService.ActionRemoteEnd, Shadows.shadowOf(context).nextStartedService.action)
        assertTrue("Push must not disconnect the output underneath the end tone", disconnected.isEmpty())
        assertTrue(GlobalCallAdmission.owns(requireNotNull(lease)))
    }

    @Test fun lateEndPushDuringToneDoesNotDisconnectTheOutputEither() {
        running(CallPhase.Ended)
        IncomingCallController().save(context, invite())
        IncomingPushHandler(context, disconnected::add).handle(account, payload("call.end"))

        assertTrue(disconnected.isEmpty())
        assertTrue(GlobalCallAdmission.owns(requireNotNull(lease)))
    }

    @Test fun failedServiceHandoffStillDisconnectsSystemCall() {
        running(CallPhase.Active)
        val unavailable = object : ContextWrapper(context) {
            override fun startForegroundService(service: Intent): ComponentName? =
                throw IllegalStateException("Foreground service unavailable")
        }
        IncomingPushHandler(unavailable, disconnected::add).handle(account, payload("call.end"))

        assertEquals(listOf(key), disconnected)
    }

    @Test fun cancellationWithoutCallServiceStillDisconnectsPendingIncomingCall() {
        GlobalCallAdmission.stage(owner)
        IncomingCallController().save(context, invite())
        try {
            IncomingPushHandler(context, disconnected::add).handle(account, payload("call.accept"))
            assertEquals(listOf(key), disconnected)
            assertNull(GlobalCallAdmission.current())
        } finally { GlobalCallAdmission.releaseStaged(owner) }
    }

    private fun running(phase: CallPhase) {
        GlobalCallAdmission.stage(owner)
        lease = requireNotNull(GlobalCallAdmission.take(owner))
        CallServiceState.publish(CallSnapshot(phase, key.callId, 1, account.id))
    }

    private fun invite() = IncomingInvite(accountId = account.id, sessionBinding = owner.sessionBinding,
        callId = key.callId, caller = "Bob", expiresAt = Instant.now().plusSeconds(60))

    private fun payload(event: String) = mapOf(
        "type" to "call_cancel", "call_id" to key.callId, "call_event" to event,
        "target_login" to account.session.login, "target_session_id" to requireNotNull(account.session.sessionId),
        "target_device_id" to DeviceIdentity.id(context),
    )
}
