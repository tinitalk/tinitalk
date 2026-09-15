package org.tinitalk.telecom

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import androidx.core.telecom.CallEndpointCompat
import org.tinitalk.call.CallDirection
import org.tinitalk.call.CallEndReason
import org.tinitalk.call.CallPhase
import org.tinitalk.call.CallUiState
import org.tinitalk.call.ConnectionHealth
import java.io.Closeable

internal enum class CallToneMode { Silent, Reaching, Ringing, Reconnecting, Busy, Congestion, Ended }

// End on the last complete beep: three 500/500 ms busy pulses, six 200/200 ms error pulses.
internal fun callFailureToneDurationMillis(mode: CallToneMode): Long =
    if (mode == CallToneMode.Busy) 2_500L else 2_200L

internal fun callToneMode(state: CallUiState): CallToneMode = when {
    state.phase == CallPhase.Ended && state.endReason == CallEndReason.Busy -> CallToneMode.Busy
    state.phase == CallPhase.Ended && state.connectedAtElapsedMs != null -> CallToneMode.Ended
    state.phase == CallPhase.Ended && state.direction == CallDirection.Outgoing &&
        state.endReason == CallEndReason.Rejected -> CallToneMode.Busy
    state.phase == CallPhase.Ended && state.direction == CallDirection.Outgoing &&
        when (state.endReason) {
            CallEndReason.TimedOut, CallEndReason.Failed,
            CallEndReason.ConnectionLost, CallEndReason.NotInContacts -> true
            else -> false
        } -> CallToneMode.Congestion
    state.phase == CallPhase.Active && state.connectionHealth == ConnectionHealth.Reconnecting -> CallToneMode.Reconnecting
    state.direction == CallDirection.Outgoing && state.phase == CallPhase.Connecting -> CallToneMode.Reaching
    state.direction == CallDirection.Outgoing && state.phase == CallPhase.Ringing -> CallToneMode.Ringing
    else -> CallToneMode.Silent
}

class CallToneController(private val handler: Handler) : Closeable {
    private var tone = createGenerator(ToneVolume)
    private var speakerTone = createGenerator(SpeakerToneVolume)
    private var useSpeaker = false
    private var terminalToneUntil = 0L
    private var mode = CallToneMode.Silent
    private var closed = false
    private var generation = 0L
    private var pulseTone: Runnable? = null
    private var retryTone: Runnable? = null

    @Synchronized
    fun update(state: CallUiState) {
        val next = callToneMode(state)
        if (closed) return
        val speaker = state.currentAudioEndpoint?.let { it.type == CallEndpointCompat.TYPE_SPEAKER } ?: useSpeaker
        val outputChanged = speaker != useSpeaker
        useSpeaker = speaker
        // Ringback is continuous. Short pulses pick up the output on their next start;
        // a terminal signal must never restart because of a late endpoint callback.
        if (next == mode && !(outputChanged && next == CallToneMode.Ringing)) return

        cancelScheduled()
        runCatching { tone?.stopTone() }
        runCatching { speakerTone?.stopTone() }
        mode = next
        terminalToneUntil = 0L
        when (next) {
            CallToneMode.Reaching -> schedulePulses(0L)
            // Remote media can disconnect before call.end arrives. Warn only if the
            // interruption persists, so an ordinary hangup does not start a loud prompt.
            CallToneMode.Reconnecting -> schedulePulses(ReconnectWarningDelayMillis)
            CallToneMode.Ringing -> startTone(ToneGenerator.TONE_SUP_RINGTONE)
            CallToneMode.Busy, CallToneMode.Congestion -> {
                val toneType = if (next == CallToneMode.Busy) {
                    ToneGenerator.TONE_SUP_BUSY
                } else ToneGenerator.TONE_SUP_CONGESTION
                startTone(toneType, callFailureToneDurationMillis(next).toInt(), terminal = true)
            }
            CallToneMode.Ended -> startTone(ToneGenerator.TONE_PROP_ACK, EndToneMillis, terminal = true)
            CallToneMode.Silent -> Unit
        }
    }

    @Synchronized
    fun stopProgressTone() {
        if (mode != CallToneMode.Reaching && mode != CallToneMode.Ringing && mode != CallToneMode.Reconnecting) return
        cancelScheduled()
        runCatching { tone?.stopTone() }
        runCatching { speakerTone?.stopTone() }
        mode = CallToneMode.Silent
    }

    private fun schedulePulses(delayMillis: Long) {
        val expectedGeneration = generation
        pulseTone = object : Runnable {
            override fun run() = synchronized(this@CallToneController) {
                if (closed || generation != expectedGeneration) return@synchronized
                startTone(ToneGenerator.TONE_PROP_PROMPT, PulseToneMillis)
                handler.postDelayed(this, PulseToneIntervalMillis)
                Unit
            }
        }.also { handler.postDelayed(it, delayMillis) }
    }

    private fun createGenerator(volume: Int): ToneGenerator? =
        runCatching { ToneGenerator(AudioManager.STREAM_VOICE_CALL, volume) }
            .onFailure { Log.w("TiniTalkTones", "Cannot create tone generator", it) }.getOrNull()

    private fun startTone(type: Int, durationMillis: Int = -1, terminal: Boolean = false, retry: Boolean = false) {
        val speaker = useSpeaker
        val generator = if (speaker) speakerTone else tone
        val started = runCatching { generator?.startTone(type, durationMillis) == true }
            .onFailure { Log.w("TiniTalkTones", "Tone $type start failed", it) }.getOrDefault(false)
        if (started) {
            if (terminal) terminalToneUntil = SystemClock.uptimeMillis() + durationMillis
            return
        }
        Log.w("TiniTalkTones", "Tone $type did not start${if (retry) " after retry" else "; retrying"}")
        if (retry) {
            if (terminal) terminalToneUntil = 0L
            return
        }
        // One bounded retry with a fresh generator. Keep the route alive while it is pending.
        if (terminal) terminalToneUntil = SystemClock.uptimeMillis() + RetryDelayMillis + durationMillis
        val expectedGeneration = generation
        retryTone = Runnable {
            synchronized(this) {
                if (closed || generation != expectedGeneration) return@synchronized
                retryTone = null
                if (terminal && SystemClock.uptimeMillis() >= terminalToneUntil) return@synchronized
                runCatching { generator?.release() }
                if (speaker) speakerTone = createGenerator(SpeakerToneVolume) else tone = createGenerator(ToneVolume)
                startTone(type, durationMillis, terminal, retry = true)
            }
        }.also { handler.postDelayed(it, RetryDelayMillis) }
    }

    private fun cancelScheduled() {
        generation++
        pulseTone?.let(handler::removeCallbacks)
        retryTone?.let(handler::removeCallbacks)
        pulseTone = null
        retryTone = null
    }

    @Synchronized
    internal fun remainingTerminalToneMillis(): Long =
        (terminalToneUntil - SystemClock.uptimeMillis()).coerceAtLeast(0L)

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        terminalToneUntil = 0L
        mode = CallToneMode.Silent
        cancelScheduled()
        runCatching { tone?.stopTone() }
        runCatching { speakerTone?.stopTone() }
        runCatching { tone?.release() }
        runCatching { speakerTone?.release() }
    }

    private companion object {
        const val ToneVolume = 80
        const val SpeakerToneVolume = 40
        const val PulseToneMillis = 180
        const val PulseToneIntervalMillis = 4_000L
        const val ReconnectWarningDelayMillis = 1_000L
        const val EndToneMillis = 400
        const val RetryDelayMillis = 100L
    }
}
