package org.tinitalk.telecom

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import org.tinitalk.call.CallDirection
import org.tinitalk.call.CallPhase
import org.tinitalk.call.CallUiState
import java.io.Closeable

class OutgoingCallToneController(private val handler: Handler) : Closeable {
    private val tone = runCatching { ToneGenerator(AudioManager.STREAM_VOICE_CALL, ToneVolume) }.getOrNull()
    private var mode = Mode.Silent
    private val reachingTone = object : Runnable {
        override fun run() {
            if (mode != Mode.Reaching) return
            runCatching { tone?.startTone(ToneGenerator.TONE_PROP_PROMPT, ReachingToneMillis) }
            handler.postDelayed(this, ReachingToneIntervalMillis)
        }
    }

    fun update(state: CallUiState) {
        val next = when {
            state.direction != CallDirection.Outgoing -> Mode.Silent
            state.phase == CallPhase.Connecting -> Mode.Reaching
            state.phase == CallPhase.Ringing -> Mode.Ringing
            else -> Mode.Silent
        }
        if (next == mode) return

        handler.removeCallbacks(reachingTone)
        runCatching { tone?.stopTone() }
        mode = next
        when (next) {
            Mode.Reaching -> handler.post(reachingTone)
            Mode.Ringing -> runCatching { tone?.startTone(ToneGenerator.TONE_SUP_RINGTONE) }
            Mode.Silent -> Unit
        }
    }

    override fun close() {
        mode = Mode.Silent
        handler.removeCallbacks(reachingTone)
        runCatching { tone?.stopTone() }
        runCatching { tone?.release() }
    }

    private enum class Mode { Silent, Reaching, Ringing }

    private companion object {
        const val ToneVolume = 60
        const val ReachingToneMillis = 180
        const val ReachingToneIntervalMillis = 4_000L
    }
}
