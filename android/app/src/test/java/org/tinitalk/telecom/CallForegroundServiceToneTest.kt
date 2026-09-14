package org.tinitalk.telecom

import android.os.Looper
import android.media.ToneGenerator
import org.robolectric.shadows.ShadowToneGenerator
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
import org.tinitalk.media.MediaConnectionState
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
    fun localHangupPlaysBeforeReleasingRoutingAndStillWaitsForTerminalDelivery() {
        val controller = Robolectric.buildService(CallForegroundService::class.java).create()
        val service = controller.get()
        try {
            GlobalCallAdmission.stage(owner)
            val lease = requireNotNull(GlobalCallAdmission.take(owner))
            leases += lease
            ReflectionHelpers.setField(service, "callOwner", owner)
            ReflectionHelpers.setField(service, "admissionLease", lease)
            CallUiStateStore.begin(owner.key, peer, CallDirection.Outgoing, CallPhase.Active)
            CallUiStateStore.onMediaConnection(MediaConnectionState.Connected)
            CallUiStateStore.sync(CallSnapshot(CallPhase.Ended, owner.key.callId, 1, owner.key.accountId), CallEndReason.LocalHangup)
            val gate = ReflectionHelpers.getField<TerminalSignalGate>(service, "terminalSignalGate")
            val settle = gate.begin {
                ReflectionHelpers.callInstanceMethod<Void>(service, "finishCallUnlessAwaitingTerminalSignal")
            }
            val count = ShadowToneGenerator.getPlayedTones().size
            ReflectionHelpers.callInstanceMethod<Void>(service, "finishCallSoon",
                ReflectionHelpers.ClassParameter.from(Long::class.javaPrimitiveType, 0L))
            assertEquals(count + 1, ShadowToneGenerator.getPlayedTones().size)
            assertEquals(ToneGenerator.TONE_PROP_ACK, ShadowToneGenerator.getPlayedTones().last().type())
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(399))
            assertTrue(GlobalCallAdmission.owns(lease))
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(51))
            assertNull(GlobalCallAdmission.current())
            assertFalse(Shadows.shadowOf(service).isStoppedBySelf)
            settle()
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(450))
            assertTrue(Shadows.shadowOf(service).isStoppedBySelf)
            assertEquals(count + 1, ShadowToneGenerator.getPlayedTones().size)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun remoteHangupKeepsRoutingUntilLateStartedToneFinishes() {
        val controller = Robolectric.buildService(CallForegroundService::class.java).create()
        val service = controller.get()
        try {
            GlobalCallAdmission.stage(owner)
            val lease = requireNotNull(GlobalCallAdmission.take(owner))
            leases += lease
            ReflectionHelpers.setField(service, "callOwner", owner)
            ReflectionHelpers.setField(service, "admissionLease", lease)
            CallUiStateStore.begin(owner.key, peer, CallDirection.Incoming, CallPhase.Active)
            CallUiStateStore.onMediaConnection(MediaConnectionState.Connected)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            // Teardown was scheduled before the terminal state reached the player.
            ReflectionHelpers.callInstanceMethod<Void>(service, "finishCallAfter",
                ReflectionHelpers.ClassParameter.from(Long::class.javaPrimitiveType, 450L),
                ReflectionHelpers.ClassParameter.from(Long::class.javaPrimitiveType, 0L))
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
            CallUiStateStore.sync(CallSnapshot(CallPhase.Ended, owner.key.callId, 1, owner.key.accountId), CallEndReason.RemoteHangup)
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150))
            assertTrue(GlobalCallAdmission.owns(lease))
            assertFalse(Shadows.shadowOf(service).isStoppedBySelf)
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
            assertTrue(Shadows.shadowOf(service).isStoppedBySelf)
        } finally {
            controller.destroy()
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
