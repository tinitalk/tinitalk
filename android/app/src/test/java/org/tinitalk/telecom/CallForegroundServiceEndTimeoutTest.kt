package org.tinitalk.telecom

import android.app.NotificationManager
import android.os.Looper
import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.tinitalk.call.*
import org.tinitalk.data.AccountId
import org.tinitalk.data.signal.SignalEvent
import org.tinitalk.media.CallMediaDispatcher
import org.tinitalk.media.MediaConnectionState
import org.tinitalk.media.MediaSession
import java.lang.reflect.Proxy
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CallForegroundServiceEndTimeoutTest {
    @Test fun blockedWorkerCannotRetainEndedCallOrPostponeCleanupWithRepeatedHangups() {
        for (reason in listOf(CallEndReason.RemoteHangup, CallEndReason.LocalHangup)) {
            val context = RuntimeEnvironment.getApplication()
            val controller = Robolectric.buildService(CallForegroundService::class.java).create()
            val service = controller.get()
            val owner = AccountCallOwner(AccountCallKey(AccountId("timeout-account"), "timeout-call"),
                CallSessionBinding("https://timeout.example", "alice", "session", "config"))
            val signal = object : SignalClient {
                override fun send(event: SignalEvent, onSettled: (() -> Unit)?) = Unit
            }
            val coordinator = CallCoordinator("alice", signal, accountId = owner.key.accountId).apply {
                restoreIncoming(owner.key.callId, acknowledgeRinging = false)
                accept()
            }
            val mediaClosed = CountDownLatch(1)
            val closeCount = AtomicInteger()
            val session = Proxy.newProxyInstance(MediaSession::class.java.classLoader,
                arrayOf(MediaSession::class.java)) { _, method, _ ->
                when (method.name) {
                    "close" -> { closeCount.incrementAndGet(); mediaClosed.countDown(); Unit }
                    "createOffer" -> "local-offer"
                    else -> Unit
                }
            } as MediaSession
            val media = ForegroundCallController(signal, owner.key.accountId, { _, _, _, _, _ -> session })
            val snapshot = CallSnapshot(CallPhase.Active, owner.key.callId, 1, owner.key.accountId)
            for (type in listOf("rtc.config", "call.accept")) {
                media.onSignalEvent(snapshot, SignalEvent("fixture-$type", owner.key.callId, type, 0L, JsonObject()))
            }
            val dispatcher = CallMediaDispatcher()
            val unblock = CountDownLatch(1)
            val workerStarted = CountDownLatch(1)
            val workerFinished = CountDownLatch(1)
            val cancelledTelecom = mutableListOf<AccountCallKey>()
            val registrar = Proxy.newProxyInstance(TelecomRegistrar::class.java.classLoader,
                arrayOf(TelecomRegistrar::class.java)) { _, method, args ->
                if (method.name == "cancel") cancelledTelecom += args!![0] as AccountCallKey
                Unit
            } as TelecomRegistrar
            ReflectionHelpers.setField(service, "telecom\$delegate", lazyOf(TelecomCallController(registrar)))
            GlobalCallAdmission.stage(owner)
            val lease = requireNotNull(GlobalCallAdmission.take(owner))
            val main = shadowOf(Looper.getMainLooper())
            try {
                ReflectionHelpers.setField(service, "callOwner", owner)
                ReflectionHelpers.setField(service, "admissionLease", lease)
                service.installRuntimeForTest(owner, coordinator, media, dispatcher)
                assertTrue(dispatcher.dispatch {
                    workerStarted.countDown()
                    try { unblock.await(10, TimeUnit.SECONDS) } finally { workerFinished.countDown() }
                })
                assertTrue(workerStarted.await(2, TimeUnit.SECONDS))
                CallUiStateStore.begin(owner.key, CallPeer("Bob"), CallDirection.Incoming, CallPhase.Active)
                CallUiStateStore.onMediaConnection(MediaConnectionState.Connected)
                main.idle()
                coordinator.fail()
                val settlement = if (reason == CallEndReason.LocalHangup) {
                    ReflectionHelpers.getField<TerminalSignalGate>(service, "terminalSignalGate").begin {
                        ReflectionHelpers.callInstanceMethod<Void>(service, "finishCallUnlessAwaitingTerminalSignal")
                    }
                } else null
                CallUiStateStore.sync(coordinator.snapshot(), reason)
                main.idleFor(Duration.ofMillis(EndMediaPreparationTimeoutMillis - 1))
                assertTrue(GlobalCallAdmission.owns(lease))
                assertEquals(1L, mediaClosed.count)
                val intent = CallForegroundService.serviceIntent(context, CallForegroundService.ActionEnd, owner)
                service.onStartCommand(intent, 0, 1)
                main.idleFor(Duration.ofMillis(1))

                assertFalse("$reason: ownership must be released by the original deadline", GlobalCallAdmission.owns(lease))
                assertEquals(listOf(owner.key), cancelledTelecom)
                val notifications = shadowOf(context.getSystemService(NotificationManager::class.java))
                assertNull(notifications.getNotification(CallForegroundService.NotificationId))
                assertTrue("$reason: actual media close cannot sit in the blocked queue", mediaClosed.await(2, TimeUnit.SECONDS))
                assertEquals(1L, unblock.count)
                assertEquals(1, closeCount.get())
                if (settlement != null) {
                    assertFalse("terminal signaling still gets its delivery opportunity", shadowOf(service).isStoppedBySelf)
                    settlement()
                    main.idleFor(Duration.ofMillis(500))
                }
                assertTrue(shadowOf(service).isStoppedBySelf)

                // The released call must not occupy the slot or revive its notification.
                val next = owner.copy(key = owner.key.copy(callId = "next-call"))
                assertTrue(GlobalCallAdmission.stage(next) is CallAdmissionAttempt.Acquired)
                val nextLease = requireNotNull(GlobalCallAdmission.take(next))
                try {
                    ReflectionHelpers.callInstanceMethod<Void>(service, "resetReleasedRuntime")
                    ReflectionHelpers.setField(service, "callOwner", next)
                    ReflectionHelpers.setField(service, "admissionLease", nextLease)
                    val nextMedia = ForegroundCallController(signal, next.key.accountId,
                        { _, _, _, _, _ -> error("No session needed for the next call") })
                    service.installRuntimeForTest(next, media = nextMedia)
                    CallUiStateStore.begin(next.key, CallPeer("Carol"), CallDirection.Outgoing, CallPhase.Active)
                    main.idle()
                    unblock.countDown()
                    assertTrue(workerFinished.await(2, TimeUnit.SECONDS))
                    main.idleFor(Duration.ofSeconds(5))
                    assertEquals("Carol", notifications.getNotification(CallForegroundService.NotificationId)
                        .extras.getString(android.app.Notification.EXTRA_TITLE))
                    assertEquals(1, closeCount.get())
                    assertEquals(next, GlobalCallAdmission.current()?.owner)
                    assertEquals(next.key, CallUiStateStore.snapshot().callKey)
                    assertSame(nextMedia, ReflectionHelpers.getField<CallRuntime>(service, "runtime").media)
                } finally { GlobalCallAdmission.release(nextLease) }
            } finally {
                unblock.countDown()
                controller.destroy()
                dispatcher.close()
                GlobalCallAdmission.release(lease)
                CallUiStateStore.reset()
                CallServiceState.reset()
            }
        }
    }
}
