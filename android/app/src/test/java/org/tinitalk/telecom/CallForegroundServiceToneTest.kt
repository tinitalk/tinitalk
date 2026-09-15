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
    @Config(shadows = [RecordingCallToneShadow::class])
    fun retriedEndSignalRetainsAudioUntilItsActualEnd() {
        val controller = Robolectric.buildService(CallForegroundService::class.java).create()
        val service = controller.get()
        try {
            GlobalCallAdmission.stage(owner)
            val lease = requireNotNull(GlobalCallAdmission.take(owner))
            leases += lease
            ReflectionHelpers.setField(service, "callOwner", owner)
            ReflectionHelpers.setField(service, "admissionLease", lease)
            val tones = ReflectionHelpers.getField<CallToneController>(service, "callTones")
            val toneHandler = ReflectionHelpers.getField<android.os.Handler>(tones, "handler")
            val toneLooper = Shadows.shadowOf(toneHandler.looper)
            toneLooper.pause()
            CallUiStateStore.begin(owner.key, peer, CallDirection.Incoming, CallPhase.Active)
            CallUiStateStore.onMediaConnection(MediaConnectionState.Connected)
            RecordingCallToneShadow.failuresRemaining = 1
            CallUiStateStore.sync(CallSnapshot(CallPhase.Ended, owner.key.callId, 1, owner.key.accountId), CallEndReason.RemoteHangup)
            ReflectionHelpers.callInstanceMethod<Void>(service, "finishCallUnlessAwaitingTerminalSignal")
            toneLooper.idleFor(Duration.ofMillis(100))
            assertEquals(ToneGenerator.TONE_PROP_ACK, ShadowToneGenerator.getPlayedTones().last().type())
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350))
            assertTrue("The original 450 ms cleanup must not cut the retried signal", GlobalCallAdmission.owns(lease))
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            assertFalse(GlobalCallAdmission.owns(lease))
            assertTrue(Shadows.shadowOf(service).isStoppedBySelf)
        } finally {
            RecordingCallToneShadow.failuresRemaining = 0
            controller.destroy()
        }
    }

    @Test
    fun reconnectPulsesDoNotNeedTheUiQueueToRun() {
        val controller = Robolectric.buildService(CallForegroundService::class.java).create()
        val service = controller.get()
        var uiRan = false
        val uiTask = Runnable { uiRan = true }
        val mainHandler = android.os.Handler(Looper.getMainLooper())
        try {
            val tones = ReflectionHelpers.getField<CallToneController>(service, "callTones")
            val toneHandler = ReflectionHelpers.getField<android.os.Handler>(tones, "handler")
            val toneLooper = Shadows.shadowOf(toneHandler.looper)
            toneLooper.pause()
            val before = ShadowToneGenerator.getPlayedTones().size
            mainHandler.post(uiTask)
            tones.update(CallUiState(phase = CallPhase.Active, connectedAtElapsedMs = 1L,
                connectionHealth = ConnectionHealth.Reconnecting))
            toneLooper.idleFor(Duration.ofSeconds(5))
            assertFalse("Sound scheduling must not depend on draining the UI queue", uiRan)
            assertEquals(2, ShadowToneGenerator.getPlayedTones().size - before)
            tones.stopProgressTone()
            toneLooper.idleFor(Duration.ofSeconds(5))
            assertEquals(2, ShadowToneGenerator.getPlayedTones().size - before)
        } finally {
            mainHandler.removeCallbacks(uiTask)
            controller.destroy()
        }
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

                val duration = if (reason == CallEndReason.Busy || reason == CallEndReason.Rejected) 2_500L else 2_200L
                Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(duration - 1))
                assertFalse("Tone cut short for $reason/$replyCode", Shadows.shadowOf(service).isStoppedBySelf)
                assertTrue(GlobalCallAdmission.owns(lease))
                Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
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
    fun endingCancelsQueuedReconnectionBeepBeforeWaitingForMedia() {
        val controller = Robolectric.buildService(CallForegroundService::class.java).create()
        val service = controller.get()
        val dispatcher = org.tinitalk.media.CallMediaDispatcher()
        val unblockMedia = java.util.concurrent.CountDownLatch(1)
        val signal = java.lang.reflect.Proxy.newProxyInstance(SignalClient::class.java.classLoader,
            arrayOf(SignalClient::class.java)) { _, _, _ -> Unit } as SignalClient
        val media = ForegroundCallController(signal, owner.key.accountId, { _, _, _, _, _ -> error("No session needed") })
        try {
            GlobalCallAdmission.stage(owner)
            val lease = requireNotNull(GlobalCallAdmission.take(owner))
            leases += lease
            ReflectionHelpers.setField(service, "callOwner", owner)
            ReflectionHelpers.setField(service, "admissionLease", lease)
            ReflectionHelpers.setField(service, "media", media)
            ReflectionHelpers.setField(service, "mediaDispatcher", dispatcher)
            assertTrue(dispatcher.dispatch { unblockMedia.await(5, java.util.concurrent.TimeUnit.SECONDS) })
            CallUiStateStore.begin(owner.key, peer, CallDirection.Outgoing, CallPhase.Active)
            CallUiStateStore.onMediaConnection(MediaConnectionState.Connected)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            val tones = ReflectionHelpers.getField<CallToneController>(service, "callTones")
            tones.update(CallUiStateStore.snapshot().copy(connectionHealth = ConnectionHealth.Reconnecting))
            val count = ShadowToneGenerator.getPlayedTones().size

            CallUiStateStore.sync(CallSnapshot(CallPhase.Ended, owner.key.callId, 1, owner.key.accountId), CallEndReason.RemoteHangup)
            ReflectionHelpers.callInstanceMethod<Void>(service, "finishCallSoon",
                ReflectionHelpers.ClassParameter.from(Long::class.javaPrimitiveType, 0L))
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))

            assertEquals("A queued loud beep must not play while the ended call waits for media", count,
                ShadowToneGenerator.getPlayedTones().size)
            unblockMedia.countDown()
            val ready = java.util.concurrent.CountDownLatch(1)
            assertTrue(dispatcher.dispatch { ready.countDown() })
            assertTrue(ready.await(2, java.util.concurrent.TimeUnit.SECONDS))
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals(count + 1, ShadowToneGenerator.getPlayedTones().size)
            assertEquals(ToneGenerator.TONE_PROP_ACK, ShadowToneGenerator.getPlayedTones().last().type())
        } finally {
            unblockMedia.countDown()
            controller.destroy()
            dispatcher.close()
        }
    }

    @Test
    fun lateEndAndTelecomCommandsCannotSkipOrInterruptTerminalTone() {
        for (action in listOf(CallForegroundService.ActionEnd, CallForegroundService.ActionTelecomInactive,
            CallForegroundService.ActionTelecomActive, CallForegroundService.ActionRemoteEnd)) {
            for (delayMillis in listOf(0L, 150L)) {
                val controller = Robolectric.buildService(CallForegroundService::class.java).create()
                val service = controller.get()
                try {
                    GlobalCallAdmission.stage(owner)
                    val lease = requireNotNull(GlobalCallAdmission.take(owner))
                    leases += lease
                    val signal = object : SignalClient {
                        override fun send(event: org.tinitalk.data.signal.SignalEvent, onSettled: (() -> Unit)?) = Unit
                    }
                    val coordinator = CallCoordinator("alice", signal, accountId = owner.key.accountId).apply {
                        restoreIncoming(owner.key.callId, acknowledgeRinging = false)
                        accept()
                    }
                    ReflectionHelpers.setField(service, "coordinator", coordinator)
                    ReflectionHelpers.setField(service, "callOwner", owner)
                    ReflectionHelpers.setField(service, "admissionLease", lease)
                    ReflectionHelpers.setField(service, "telecomCallKey", owner.key)
                    CallUiStateStore.begin(owner.key, peer, CallDirection.Incoming, CallPhase.Active)
                    CallUiStateStore.onMediaConnection(MediaConnectionState.Connected)
                    Shadows.shadowOf(Looper.getMainLooper()).idle()
                    val count = ShadowToneGenerator.getPlayedTones().size
                    coordinator.fail()
                    CallUiStateStore.sync(coordinator.snapshot(), CallEndReason.RemoteHangup)
                    if (delayMillis > 0) Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(delayMillis))
                    val intent = android.content.Intent(context, CallForegroundService::class.java).setAction(action)
                        .putExtra("account_id", owner.key.accountId.value)
                        .putExtra("call_id", owner.key.callId)
                        .putExtra("session_server_url", owner.sessionBinding.serverUrl)
                        .putExtra("session_login", owner.sessionBinding.login)
                        .putExtra("session_id", owner.sessionBinding.sessionId)
                        .putExtra("config_id", owner.sessionBinding.configId)
                    service.onStartCommand(intent, 0, 1)
                    Shadows.shadowOf(Looper.getMainLooper()).idle()
                    assertFalse("$action at $delayMillis ms stopped the tone's service", Shadows.shadowOf(service).isStoppedBySelf)
                    assertTrue(GlobalCallAdmission.owns(lease))
                    assertEquals(count + 1, ShadowToneGenerator.getPlayedTones().size)
                    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(399 - delayMillis))
                    assertTrue(GlobalCallAdmission.owns(lease))
                    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(201))
                    assertFalse(GlobalCallAdmission.owns(lease))
                    assertEquals(count + 1, ShadowToneGenerator.getPlayedTones().size)
                } finally { controller.destroy() }
            }
        }
    }

    @Test
    fun voiceIsSilencedBeforeToneAndMediaIsClosedOnlyAfterTone() {
        val controller = Robolectric.buildService(CallForegroundService::class.java).create()
        val service = controller.get()
        val calls = java.util.Collections.synchronizedList(mutableListOf<String>())
        val mediaClosed = java.util.concurrent.CountDownLatch(1)
        val session = java.lang.reflect.Proxy.newProxyInstance(
            org.tinitalk.media.MediaSession::class.java.classLoader,
            arrayOf(org.tinitalk.media.MediaSession::class.java),
        ) { _, method, args ->
            if (method.name == "setActive") calls.add("active:${args!![0]}")
            if (method.name == "close") { calls.add("close"); mediaClosed.countDown() }
            Unit
        } as org.tinitalk.media.MediaSession
        val signal = java.lang.reflect.Proxy.newProxyInstance(SignalClient::class.java.classLoader,
            arrayOf(SignalClient::class.java)) { _, _, _ -> Unit } as SignalClient
        val media = ForegroundCallController(signal, owner.key.accountId, { _, _, _, _, _ -> session })
        val dispatcher = org.tinitalk.media.CallMediaDispatcher()
        ReflectionHelpers.setField(media, "session", session)
        try {
            GlobalCallAdmission.stage(owner)
            val lease = requireNotNull(GlobalCallAdmission.take(owner))
            leases += lease
            ReflectionHelpers.setField(service, "callOwner", owner)
            ReflectionHelpers.setField(service, "admissionLease", lease)
            ReflectionHelpers.setField(service, "media", media)
            ReflectionHelpers.setField(service, "mediaDispatcher", dispatcher)
            CallUiStateStore.begin(owner.key, peer, CallDirection.Outgoing, CallPhase.Active)
            CallUiStateStore.onMediaConnection(MediaConnectionState.Connected)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            val count = ShadowToneGenerator.getPlayedTones().size
            CallUiStateStore.sync(CallSnapshot(CallPhase.Ended, owner.key.callId, 1, owner.key.accountId), CallEndReason.LocalHangup)
            ReflectionHelpers.callInstanceMethod<Void>(service, "finishCallSoon",
                ReflectionHelpers.ClassParameter.from(Long::class.javaPrimitiveType, 0L))
            val ready = java.util.concurrent.CountDownLatch(1)
            assertTrue(dispatcher.dispatch { ready.countDown() })
            assertTrue(ready.await(2, java.util.concurrent.TimeUnit.SECONDS))
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf("active:false"), calls.toList())
            assertEquals(count + 1, ShadowToneGenerator.getPlayedTones().size)
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(399))
            assertFalse(calls.contains("close"))
            assertTrue(GlobalCallAdmission.owns(lease))
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(51))
            assertTrue(mediaClosed.await(2, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(listOf("active:false", "close"), calls.toList())
        } finally { controller.destroy(); dispatcher.close() }
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
