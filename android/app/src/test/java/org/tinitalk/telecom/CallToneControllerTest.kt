package org.tinitalk.telecom

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowToneGenerator
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.util.ReflectionHelpers
import org.tinitalk.call.CallDirection
import org.tinitalk.call.CallEndReason
import org.tinitalk.call.CallPhase
import org.tinitalk.call.CallUiState
import org.tinitalk.call.ConnectionHealth
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [RecordingCallToneShadow::class])
class CallToneControllerTest {
    @Before fun resetFailures() {
        RecordingCallToneShadow.failuresRemaining = 0
        RecordingCallToneShadow.throwOnStart = false
        RecordingCallToneShadow.attempts = 0
    }

    private val rejected = CallUiState(
        direction = CallDirection.Outgoing, phase = CallPhase.Ended, endReason = CallEndReason.Rejected,
    )

    @Test
    fun busyAndRejectedCallsShareBoundedToneWhileOtherFailuresUseCongestion() {
        for ((reason, expected) in listOf(
            CallEndReason.Busy to ToneGenerator.TONE_SUP_BUSY,
            CallEndReason.Rejected to ToneGenerator.TONE_SUP_BUSY,
            CallEndReason.TimedOut to ToneGenerator.TONE_SUP_CONGESTION,
            CallEndReason.Failed to ToneGenerator.TONE_SUP_CONGESTION,
            CallEndReason.ConnectionLost to ToneGenerator.TONE_SUP_CONGESTION,
            CallEndReason.NotInContacts to ToneGenerator.TONE_SUP_CONGESTION,
        )) {
            val controller = CallToneController(Handler(Looper.getMainLooper()))
            val playedBefore = ShadowToneGenerator.getPlayedTones().size
            try {
                val state = rejected.copy(endReason = reason)
                controller.update(state)
                controller.update(state)
                val tone = ShadowToneGenerator.getPlayedTones().drop(playedBefore).single()
                assertEquals("Wrong signal for $reason", expected, tone.type())
                // Three complete 500 ms busy beeps; six complete 200 ms error beeps.
                assertEquals(Duration.ofMillis(if (expected == ToneGenerator.TONE_SUP_BUSY) 2_500 else 2_200), tone.duration())
                assertEquals(AudioManager.STREAM_VOICE_CALL, controller.toneShadow().streamType)
            } finally {
                controller.close()
            }
        }
    }

    @Test
    fun failedStartIsRetriedOnceAndKeepsTheWholeTerminalSignalAlive() {
        val states = listOf(
            Triple(rejected, 2_500L, 80),
            Triple(rejected.copy(endReason = CallEndReason.Failed), 2_200L, 80),
            Triple(rejected.copy(connectedAtElapsedMs = 1L, endReason = CallEndReason.RemoteHangup), 400L, 80),
        )
        for ((state, duration, volume) in states) for (throws in listOf(false, true)) {
            val controller = CallToneController(Handler(Looper.getMainLooper()))
            try {
                RecordingCallToneShadow.failuresRemaining = 1
                RecordingCallToneShadow.throwOnStart = throws
                val count = ShadowToneGenerator.getPlayedTones().size
                controller.update(state)
                assertEquals(count, ShadowToneGenerator.getPlayedTones().size)
                assertTrue("Keep the audio route while a retry is pending", controller.remainingTerminalToneMillis() > 0)
                Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
                assertEquals(count + 1, ShadowToneGenerator.getPlayedTones().size)
                assertEquals(duration, controller.remainingTerminalToneMillis())
                val generator = ReflectionHelpers.getField<ToneGenerator>(controller, "tone")
                assertEquals(volume, Shadow.extract<RecordingCallToneShadow>(generator).volume)
                controller.update(state)
                Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(duration - 1))
                assertEquals(1L, controller.remainingTerminalToneMillis())
                assertEquals(count + 1, ShadowToneGenerator.getPlayedTones().size)
            } finally { controller.close() }
        }
    }

    @Test
    fun persistentFailureDoesNotLoopOrKeepTheCallAliveForever() {
        val controller = CallToneController(Handler(Looper.getMainLooper()))
        try {
            RecordingCallToneShadow.failuresRemaining = 100
            controller.update(rejected)
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
            assertEquals(2, RecordingCallToneShadow.attempts)
            assertEquals(0L, controller.remainingTerminalToneMillis())
        } finally { controller.close() }
    }

    @Test
    fun expiredTerminalRetryCannotStartAfterTheAudioRouteWasReleased() {
        val controller = CallToneController(Handler(Looper.getMainLooper()))
        try {
            RecordingCallToneShadow.failuresRemaining = 1
            val count = ShadowToneGenerator.getPlayedTones().size
            controller.update(rejected.copy(connectedAtElapsedMs = 1L, endReason = CallEndReason.RemoteHangup))
            ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
            assertEquals(0L, controller.remainingTerminalToneMillis())
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals(count, ShadowToneGenerator.getPlayedTones().size)
        } finally { controller.close() }
    }

    @Test
    fun answeringOrClosingCancelsFailedProgressToneRetry() {
        for (close in listOf(false, true)) {
            val controller = CallToneController(Handler(Looper.getMainLooper()))
            try {
                RecordingCallToneShadow.failuresRemaining = 1
                controller.update(CallUiState(direction = CallDirection.Outgoing, phase = CallPhase.Ringing))
                val attempts = RecordingCallToneShadow.attempts
                if (close) controller.close() else controller.update(CallUiState(phase = CallPhase.Active))
                Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
                assertEquals(attempts, RecordingCallToneShadow.attempts)
            } finally { controller.close() }
        }
    }

    @Test
    fun shortDisconnectBeforeRemoteHangupDoesNotPrependALoudPrompt() {
        val controller = CallToneController(Handler(Looper.getMainLooper()))
        val active = CallUiState(direction = CallDirection.Incoming, phase = CallPhase.Active,
            connectedAtElapsedMs = 1L, connectionHealth = ConnectionHealth.Good)
        try {
            val count = ShadowToneGenerator.getPlayedTones().size
            controller.update(active.copy(connectionHealth = ConnectionHealth.Reconnecting))
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
            controller.update(active.copy(phase = CallPhase.Ended, endReason = CallEndReason.RemoteHangup))
            assertEquals("The end signal must not wait for the reconnect warning", count + 1,
                ShadowToneGenerator.getPlayedTones().size)
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
            val played = ShadowToneGenerator.getPlayedTones().drop(count)
            assertEquals(listOf(ToneGenerator.TONE_PROP_ACK), played.map { it.type() })
            assertEquals(80, controller.toneShadow().volume)
        } finally { controller.close() }
    }

    @Test
    fun sustainedDisconnectStillBeepsButRecoveryCancelsPendingWarning() {
        val controller = CallToneController(Handler(Looper.getMainLooper()))
        val active = CallUiState(phase = CallPhase.Active, connectedAtElapsedMs = 1L,
            connectionHealth = ConnectionHealth.Good)
        try {
            val count = ShadowToneGenerator.getPlayedTones().size
            controller.update(active.copy(connectionHealth = ConnectionHealth.Reconnecting))
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
            controller.update(active)
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
            assertEquals(count, ShadowToneGenerator.getPlayedTones().size)

            controller.update(active.copy(connectionHealth = ConnectionHealth.Reconnecting))
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(999))
            assertEquals(count, ShadowToneGenerator.getPlayedTones().size)
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
            assertEquals(count + 1, ShadowToneGenerator.getPlayedTones().size)
            assertEquals(ToneGenerator.TONE_PROP_PROMPT, ShadowToneGenerator.getPlayedTones().last().type())
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4))
            assertEquals(count + 2, ShadowToneGenerator.getPlayedTones().size)
        } finally { controller.close() }
    }

    @Test
    fun nextCallAndClosingStopOldToneAndCancelPendingPulses() {
        val controller = CallToneController(Handler(Looper.getMainLooper()))
        val shadow = controller.toneShadow()
        controller.update(rejected)
        val beforeNextCall = shadow.stopCount
        controller.update(CallUiState(direction = CallDirection.Incoming, phase = CallPhase.Ringing))
        assertEquals(beforeNextCall + 1, shadow.stopCount)
        controller.update(CallUiState(direction = CallDirection.Outgoing, phase = CallPhase.Connecting))
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val beforeClose = shadow.stopCount
        val playedBeforeClose = ShadowToneGenerator.getPlayedTones().size
        controller.close()
        assertEquals(beforeClose + 1, shadow.stopCount)
        assertTrue(shadow.released)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        assertEquals(playedBeforeClose, ShadowToneGenerator.getPlayedTones().size)
    }

    private fun CallToneController.toneShadow(): RecordingCallToneShadow =
        Shadow.extract(ReflectionHelpers.getField<ToneGenerator>(this, "tone"))
}

@Implements(ToneGenerator::class)
class RecordingCallToneShadow : ShadowToneGenerator() {
    companion object {
        var failuresRemaining = 0
        var throwOnStart = false
        var attempts = 0
    }
    var streamType = -1
    var volume = -1
    var stopCount = 0
    var released = false

    @Implementation
    fun __constructor__(streamType: Int, volume: Int) {
        this.streamType = streamType
        this.volume = volume
    }

    @Implementation
    override fun startTone(toneType: Int, durationMs: Int): Boolean {
        attempts++
        if (failuresRemaining > 0) {
            failuresRemaining--
            if (throwOnStart) throw IllegalStateException("Audio output unavailable")
            return false
        }
        return super.startTone(toneType, durationMs)
    }

    @Implementation
    fun stopTone() { stopCount++ }

    @Implementation
    fun release() { released = true }
}
