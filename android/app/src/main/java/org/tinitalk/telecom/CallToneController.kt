package org.tinitalk.telecom

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import org.tinitalk.call.CallDirection
import org.tinitalk.call.CallEndReason
import org.tinitalk.call.CallPhase
import org.tinitalk.call.CallUiState
import org.tinitalk.call.ConnectionHealth
import java.io.Closeable

internal enum class CallToneMode { Silent, Reaching, Ringing, Reconnecting, Busy, Ended }

internal fun callToneMode(state: CallUiState): CallToneMode = when {
    state.phase == CallPhase.Ended && state.endReason == CallEndReason.Busy -> CallToneMode.Busy
    state.phase == CallPhase.Ended && state.connectedAtElapsedMs != null -> CallToneMode.Ended
    state.phase == CallPhase.Active && state.connectionHealth == ConnectionHealth.Reconnecting -> CallToneMode.Reconnecting
    state.direction == CallDirection.Outgoing && state.phase == CallPhase.Connecting -> CallToneMode.Reaching
    state.direction == CallDirection.Outgoing && state.phase == CallPhase.Ringing -> CallToneMode.Ringing
    else -> CallToneMode.Silent
}

class CallToneController(private val handler: Handler) : Closeable {
    private val tone = runCatching { ToneGenerator(AudioManager.STREAM_VOICE_CALL, ToneVolume) }.getOrNull()
    private var mode = CallToneMode.Silent
    private val pulseTone = object : Runnable {
        override fun run() {
            if (mode != CallToneMode.Reaching && mode != CallToneMode.Reconnecting) return
            runCatching { tone?.startTone(ToneGenerator.TONE_PROP_PROMPT, PulseToneMillis) }
            handler.postDelayed(this, PulseToneIntervalMillis)
        }
    }

    fun update(state: CallUiState) {
        val next = callToneMode(state)
        if (next == mode) return

        handler.removeCallbacks(pulseTone)
        runCatching { tone?.stopTone() }
        mode = next
        when (next) {
            CallToneMode.Reaching, CallToneMode.Reconnecting -> handler.post(pulseTone)
            CallToneMode.Ringing -> runCatching { tone?.startTone(ToneGenerator.TONE_SUP_RINGTONE) }
            CallToneMode.Busy -> runCatching { tone?.startTone(ToneGenerator.TONE_SUP_BUSY) }
            CallToneMode.Ended -> runCatching { tone?.startTone(ToneGenerator.TONE_PROP_ACK, EndToneMillis) }
            CallToneMode.Silent -> Unit
        }
    }

    override fun close() {
        mode = CallToneMode.Silent
        handler.removeCallbacks(pulseTone)
        runCatching { tone?.stopTone() }
        runCatching { tone?.release() }
    }

    private companion object {
        const val ToneVolume = 60
        const val PulseToneMillis = 180
        const val PulseToneIntervalMillis = 4_000L
        const val EndToneMillis = 400
    }
}
