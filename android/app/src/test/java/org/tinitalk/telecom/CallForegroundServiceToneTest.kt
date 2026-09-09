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
import org.robolectric.util.ReflectionHelpers
import org.tinitalk.call.*
import org.tinitalk.data.AccountId
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CallForegroundServiceToneTest {
    private val context = RuntimeEnvironment.getApplication()
    private val owner = AccountCallOwner(
        AccountCallKey(AccountId("tone-account"), "tone-call"),
        CallSessionBinding("https://tone.example", "alice", "session", "config"),
    )
    private val peer = CallPeer("Bob")
    private val leases = mutableListOf<CallAdmissionLease>()

    @After fun cleanup() {
        leases.forEach(GlobalCallAdmission::release)
        CallReplyResultStore(context).clear()
        CallUiStateStore.reset()
        CallServiceState.reset()
    }

    @Test
    fun unsuccessfulOutgoingAttemptsKeepAudioResourcesUntilTerminalToneEnds() {
        val outcomes = (listOf(null) + CallReplyCode.entries).map { CallEndReason.Rejected to it } +
            listOf(CallEndReason.Busy, CallEndReason.TimedOut, CallEndReason.Failed, CallEndReason.ConnectionLost,
                CallEndReason.NotInContacts).map { it to null }
        val cases = outcomes.map { (reason, replyCode) ->
            val phase = if (reason == CallEndReason.Rejected || reason == CallEndReason.TimedOut) {
                CallPhase.Ringing
            } else CallPhase.Connecting
            Triple(reason, replyCode, phase)
        } + Triple(CallEndReason.TimedOut, null, CallPhase.Connecting)
        for ((reason, replyCode, phase) in cases) {
            val controller = Robolectric.buildService(CallForegroundService::class.java).create()
            val service = controller.get()
            try {
                GlobalCallAdmission.stage(owner)
                val lease = requireNotNull(GlobalCallAdmission.take(owner))
                leases += lease
                ReflectionHelpers.setField(service, "callOwner", owner)
                ReflectionHelpers.setField(service, "admissionLease", lease)
                CallUiStateStore.begin(owner.key, peer, CallDirection.Outgoing, phase)
                replyCode?.let { CallReplyResultStore(context).save(CallReplyResult(owner.key, peer, it)) }
                CallUiStateStore.sync(CallSnapshot(CallPhase.Ended, owner.key.callId, 1, owner.key.accountId), reason)
                ReflectionHelpers.callInstanceMethod<Void>(service, "finishCallUnlessAwaitingTerminalSignal")

                Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2_000))
                assertFalse("Tone cut short for $reason/$replyCode", Shadows.shadowOf(service).isStoppedBySelf)
                assertTrue(GlobalCallAdmission.owns(lease))
                Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
                assertTrue(Shadows.shadowOf(service).isStoppedBySelf)
                assertNull(GlobalCallAdmission.current())
            } finally {
                controller.destroy()
                CallUiStateStore.reset()
                CallReplyResultStore(context).clear()
            }
        }
    }

    @Test
    fun oldToneFinishCallbackCannotStopReplacedRuntime() {
        val controller = Robolectric.buildService(CallForegroundService::class.java).create()
        val service = controller.get()
        try {
            ReflectionHelpers.setField(service, "callOwner", owner)
            CallUiStateStore.begin(owner.key, peer, CallDirection.Outgoing, CallPhase.Ended)
            CallUiStateStore.sync(CallSnapshot(CallPhase.Ended, owner.key.callId, 1, owner.key.accountId), CallEndReason.Rejected)
            ReflectionHelpers.callInstanceMethod<Void>(service, "finishCallUnlessAwaitingTerminalSignal")
            ReflectionHelpers.setField(service, "runtimeGeneration", 1L)
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
            assertFalse(Shadows.shadowOf(service).isStoppedBySelf)
        } finally {
            controller.destroy()
        }
    }
}
