package org.tinitalk.telecom

import android.app.Application
import android.os.Handler
import android.os.Looper
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import org.tinitalk.call.*
import org.tinitalk.data.AccountId
import org.tinitalk.media.CallStats
import org.tinitalk.media.MediaConnectionState
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CallStatsMonitorTest {
    private val main = shadowOf(Looper.getMainLooper())
    private val mediaQueue = ArrayDeque<() -> Unit>()
    private val callbacks = ArrayDeque<(CallStats) -> Unit>()
    private var sourceCurrent = true
    private var dispatchAccepted = true
    private var failStats = false
    private var reads = 0
    private val source = CallStatsSource(
        dispatch = { task -> dispatchAccepted.also { if (it) mediaQueue.addLast(task) } },
        getStats = { callback ->
            reads++
            check(!failStats) { "media is closing" }
            callbacks.addLast(callback)
        },
        isCurrent = { sourceCurrent },
    )
    private val monitor = CallStatsMonitor(Handler(Looper.getMainLooper())) { source }
    private var key = AccountCallKey(AccountId("account-a"), "call-1")
    private lateinit var session: MediaStatsSession

    @Before fun setUp() {
        beginCall(key)
        session = monitor.openSession(key.callId)
        connection(MediaConnectionState.Connected)
    }

    @After fun tearDown() {
        monitor.stop()
        CallServiceState.reset()
        CallUiStateStore.reset()
    }

    @Test fun pollingWaitsOneSecondAndNeverOverlapsRequestsOrDuplicatesTheTimer() {
        monitor.start()
        monitor.start()
        main.idleFor(Duration.ofMillis(999))
        assertTrue(mediaQueue.isEmpty())
        main.idleFor(Duration.ofMillis(1))
        assertEquals(1, mediaQueue.size)
        runMedia()
        main.idleFor(Duration.ofSeconds(3))
        runMedia()
        assertEquals(1, reads)
        callbacks.removeFirst()(CallStats())
        main.idle()
        tick()
        assertEquals(2, reads)
    }

    @Test fun samplesKeepQualityHysteresisRouteAndDiagnosticLogging() {
        monitor.start()
        val poor = CallStats(rttMs = 600, localCandidateType = "relay", remoteCandidateType = "host")
        report(poor)
        report(poor)
        assertEquals(ConnectionHealth.Good, CallUiStateStore.snapshot().connectionHealth)
        report(poor.copy(videoDiagnostics = listOf("video sample")))
        assertEquals(ConnectionHealth.Poor, CallUiStateStore.snapshot().connectionHealth)
        assertEquals(CallTransportRoute.Turn, CallUiStateStore.snapshot().transportRoute)
        assertTrue(ShadowLog.getLogsForTag("TiniTalkCall").any { it.msg.contains("rtt_ms=600") })
        assertTrue(ShadowLog.getLogsForTag("TiniTalkVideo").any { it.msg == "video sample" })
        report(CallStats())
        assertEquals(ConnectionHealth.Poor, CallUiStateStore.snapshot().connectionHealth)
        report(CallStats(localCandidateType = "host", remoteCandidateType = "srflx"))
        assertEquals(ConnectionHealth.Good, CallUiStateStore.snapshot().connectionHealth)
        assertEquals(CallTransportRoute.Direct, CallUiStateStore.snapshot().transportRoute)
    }

    @Test fun reconnectDropsOldSampleAndStartsANewQualityWindow() {
        monitor.start()
        val poor = CallStats(rttMs = 600, localCandidateType = "relay", remoteCandidateType = "host")
        report(poor)
        report(poor)
        tick()
        assertEquals(1, callbacks.size)
        val old = callbacks.removeFirst()
        connection(MediaConnectionState.Disconnected)
        tick()
        assertTrue(callbacks.isEmpty())
        old(poor)
        main.idle()
        assertEquals(ConnectionHealth.Reconnecting, CallUiStateStore.snapshot().connectionHealth)
        assertEquals(CallTransportRoute.Unknown, CallUiStateStore.snapshot().transportRoute)
        connection(MediaConnectionState.Connected)
        report(poor)
        assertEquals(ConnectionHealth.Good, CallUiStateStore.snapshot().connectionHealth)
        // A duplicate Connected event must not restart the three-sample window.
        connection(MediaConnectionState.Connected)
        report(poor)
        report(poor)
        assertEquals(ConnectionHealth.Poor, CallUiStateStore.snapshot().connectionHealth)
    }

    @Test fun stopRemovesTheTimerAndRejectsThePendingResult() {
        monitor.start()
        tick()
        assertEquals(1, callbacks.size)
        val old = callbacks.removeFirst()
        main.idleFor(Duration.ofMillis(500))
        monitor.stop()
        assertFalse(monitor.owns(session))
        old(CallStats(localCandidateType = "relay", remoteCandidateType = "host"))
        main.idle()
        assertEquals(1, reads)
        assertEquals(CallTransportRoute.Unknown, CallUiStateStore.snapshot().transportRoute)

        session = monitor.openSession(key.callId)
        connection(MediaConnectionState.Connected)
        monitor.start()
        // The old timer was due half a second before the new timer.
        main.idleFor(Duration.ofMillis(500))
        runMedia()
        assertEquals(1, reads)
        main.idleFor(Duration.ofMillis(500))
        runMedia()
        assertEquals(2, reads)
        monitor.stop()
        main.idleFor(Duration.ofSeconds(5))
        runMedia()
        assertEquals(2, reads)
    }

    @Test fun replacementWithTheSameCallIdCannotPublishThePreviousAccountsSample() {
        monitor.start()
        tick()
        assertEquals(1, callbacks.size)
        val old = callbacks.removeFirst()
        val oldSession = session
        monitor.stop()
        key = AccountCallKey(AccountId("account-b"), "call-1")
        beginCall(key)
        session = monitor.openSession(key.callId)
        assertFalse(monitor.owns(oldSession))
        assertNull(monitor.onConnection(oldSession, MediaConnectionState.Disconnected))
        connection(MediaConnectionState.Connected)
        monitor.start()
        tick()
        old(CallStats(localCandidateType = "relay", remoteCandidateType = "host"))
        main.idle()
        assertEquals(CallTransportRoute.Unknown, CallUiStateStore.snapshot().transportRoute)
        tick()
        assertEquals(1, callbacks.size)
        callbacks.removeFirst()(CallStats(localCandidateType = "host", remoteCandidateType = "host"))
        main.idle()
        assertEquals(key, CallUiStateStore.snapshot().callKey)
        assertEquals(CallTransportRoute.Direct, CallUiStateStore.snapshot().transportRoute)
    }

    @Test fun rejectedDispatchAndFailedReadDoNotBlockLaterPolling() {
        monitor.start()
        dispatchAccepted = false
        tick()
        assertEquals(0, reads)
        dispatchAccepted = true
        failStats = true
        tick()
        assertEquals(1, reads)
        failStats = false
        report(CallStats(localCandidateType = "host", remoteCandidateType = "host"))
        assertEquals(2, reads)
        assertEquals(CallTransportRoute.Direct, CallUiStateStore.snapshot().transportRoute)
    }

    @Test fun obsoleteRuntimeIsCheckedBothBeforeTheReadAndBeforePublication() {
        monitor.start()
        main.idleFor(Duration.ofSeconds(1))
        assertEquals(1, mediaQueue.size)
        sourceCurrent = false
        runMedia()
        assertEquals(0, reads)
        sourceCurrent = true
        tick()
        assertEquals(1, callbacks.size)
        sourceCurrent = false
        callbacks.removeFirst()(CallStats(localCandidateType = "relay", remoteCandidateType = "host"))
        main.idle()
        assertEquals(CallTransportRoute.Unknown, CallUiStateStore.snapshot().transportRoute)
    }

    private fun beginCall(key: AccountCallKey) {
        CallServiceState.publish(CallSnapshot(phase = CallPhase.Active, callId = key.callId, accountId = key.accountId))
        CallUiStateStore.begin(key, CallPeer("Аня", "anna"), CallDirection.Outgoing, CallPhase.Active)
    }

    private fun connection(state: MediaConnectionState) {
        val epoch = requireNotNull(monitor.onConnection(session, state))
        monitor.resetHealthOnConnection(epoch)
        CallUiStateStore.onMediaConnection(state)
    }

    private fun runMedia() { while (mediaQueue.isNotEmpty()) mediaQueue.removeFirst().invoke() }
    private fun tick() { main.idleFor(Duration.ofSeconds(1)); runMedia() }
    private fun report(stats: CallStats) {
        tick()
        assertEquals(1, callbacks.size)
        callbacks.removeFirst()(stats)
        main.idle()
    }
}
