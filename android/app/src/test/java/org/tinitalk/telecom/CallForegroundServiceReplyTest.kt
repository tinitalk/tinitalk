package org.tinitalk.telecom

import android.os.Looper
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.tinitalk.call.*
import org.tinitalk.data.AccountId
import org.tinitalk.data.signal.SignalEvent
import org.tinitalk.push.IncomingInvite
import java.time.Instant
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CallForegroundServiceReplyTest {
    private val context = RuntimeEnvironment.getApplication()
    private val incoming = IncomingCallController()
    private val leases = mutableListOf<CallAdmissionLease>()
    private val invite = IncomingInvite(
        accountId = AccountId("reply-account"),
        sessionBinding = CallSessionBinding("https://reply.example", "alice", "session", "config"),
        callId = "reply-call", caller = "Bob", callerLogin = "bob",
        expiresAt = Instant.parse("2099-01-01T00:00:00Z"),
    )
    private val eventId = "018f7d51-3f90-7e63-b657-4a83a6a90008"

    @After fun cleanup() {
        incoming.clear(context, invite.owner)
        leases.forEach(GlobalCallAdmission::release)
        CallUiStateStore.reset()
        CallServiceState.reset()
    }

    @Test fun pendingReplySurvivesResourceReleaseAndServiceRecreationUntilAck() {
        incoming.save(context, invite, IncomingCallController.ActionReject, CallReplyCode.WillCallBack, eventId)
        val firstSignal = CapturingSignal()
        val first = startReject(firstSignal)
        assertEquals(CallPhase.Ended, CallUiStateStore.snapshot().phase)
        assertNull(GlobalCallAdmission.current()) // Resources are free while delivery waits.
        val saved = requireNotNull(incoming.load(context))
        assertEquals(eventId, saved.terminalEventId)
        first.destroy()
        assertEquals(saved, incoming.load(context))

        val secondSignal = CapturingSignal()
        val second = startReject(secondSignal)
        assertEquals(firstSignal.reply?.id, secondSignal.reply?.id)
        assertEquals(firstSignal.reply?.payload, secondSignal.reply?.payload)
        secondSignal.result(SignalSendResult.Acknowledged)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertNull(incoming.load(context))
        second.destroy()
    }

    @Test fun rejectedDeliveryClearsPendingOnlyAfterServerResponse() {
        incoming.save(context, invite, IncomingCallController.ActionReject, CallReplyCode.CannotTalk, eventId)
        val signal = CapturingSignal()
        val service = startReject(signal)
        assertNotNull(incoming.load(context))
        signal.result(SignalSendResult.Rejected)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertNull(incoming.load(context))
        service.destroy()
    }

    @Test fun unconfirmedTimeoutFinishesDurableAttempt() {
        incoming.save(context, invite, IncomingCallController.ActionReject, CallReplyCode.CallMeLater, eventId)
        val service = startReject(CapturingSignal())
        assertNotNull(incoming.load(context))
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(21, TimeUnit.SECONDS)
        assertNull(incoming.load(context))
        service.destroy()
    }

    private fun startReject(signal: CapturingSignal): org.robolectric.android.controller.ServiceController<CallForegroundService> {
        val controller = Robolectric.buildService(CallForegroundService::class.java).create()
        val service = controller.get()
        GlobalCallAdmission.stage(invite.owner)
        val lease = requireNotNull(GlobalCallAdmission.take(invite.owner))
        leases += lease
        fun field(name: String, value: Any) {
            service.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
        }
        field("coordinator", CallCoordinator("alice", signal, accountId = invite.accountId))
        field("telecomCallKey", invite.key)
        field("callOwner", invite.owner)
        field("admissionLease", lease)
        val intent = Shadows.shadowOf(incoming.actionIntent(context, IncomingCallController.ActionReject,
            invite, incoming.load(context)?.replyCode, eventId)).savedIntent
            .setClass(service, CallForegroundService::class.java).setAction(CallForegroundService.ActionReject)
        service.onStartCommand(intent, 0, 1)
        return controller
    }

    private class CapturingSignal : SignalClient {
        var reply: SignalEvent? = null
        lateinit var result: (SignalSendResult) -> Unit
        override fun send(event: SignalEvent, onSettled: (() -> Unit)?) = Unit
        override fun sendTracked(event: SignalEvent, onResult: (SignalSendResult) -> Unit) {
            reply = event
            result = onResult
        }
    }
}
