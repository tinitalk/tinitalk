package org.tinitalk.telecom

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowToneGenerator
import org.robolectric.util.ReflectionHelpers
import org.tinitalk.call.CallDirection
import org.tinitalk.call.CallEndReason
import org.tinitalk.call.CallPhase
import org.tinitalk.call.CallUiState
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [RecordingCallToneShadow::class])
class CallToneControllerTest {
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
                assertEquals(Duration.ofMillis(2_200), tone.duration())
                assertEquals(AudioManager.STREAM_VOICE_CALL, controller.toneShadow().streamType)
            } finally {
                controller.close()
            }
        }
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
    var streamType = -1
    var stopCount = 0
    var released = false

    @Implementation
    fun __constructor__(streamType: Int, volume: Int) {
        this.streamType = streamType
    }

    @Implementation
    fun stopTone() { stopCount++ }

    @Implementation
    fun release() { released = true }
}
