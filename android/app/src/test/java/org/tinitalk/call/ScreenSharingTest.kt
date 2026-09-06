package org.tinitalk.call

import android.app.Application
import android.content.Intent
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.tinitalk.data.AccountId
import org.tinitalk.data.signal.SignalEvent
import org.tinitalk.media.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ScreenSharingTest {
    private val callId = "00000000-0000-0000-0000-000000000099"
    private val sent = mutableListOf<SignalEvent>()
    private val media = ScreenMedia()
    private var state = CallVideoState<VideoRenderSource>()
    private val controller = ForegroundCallController(
        signal = object : SignalClient {
            override fun send(event: SignalEvent, onSettled: (() -> Unit)?) { sent += event }
        },
        mediaFactory = { _, _, _, _, _, _ -> media },
        accountId = AccountId("test"), selfLogin = "alice",
        onVideoStateChanged = { state = it }, prepareScreenStart = { true },
    )

    private fun prepare() {
        event("call.accept")
        event("rtc.config", JsonObject().apply {
            add("ice_servers", JsonArray())
            addProperty("video_allowed", true)
            addProperty("screen_sharing_allowed", true)
        })
        controller.onMediaConnection(callId, 1, MediaConnectionState.Connected)
    }

    private fun event(type: String, payload: JsonObject = JsonObject()) {
        controller.onSignalEvent(CallSnapshot(CallPhase.Active, callId, 1),
            SignalEvent("00000000-0000-0000-0000-000000000001", callId, type, 1, payload))
    }

    private fun grant(id: String, presenter: String = "alice") = event("rtc.screen", JsonObject().apply {
        addProperty("enabled", true)
        addProperty("share_id", id)
        addProperty("presenter_id", presenter)
    })

    @Test fun captureRequiresConsentAndMatchingServerGrant() {
        prepare()
        grant("00000000-0000-0000-0000-000000000088")
        assertEquals(0, media.starts)
        controller.requestScreen(callId, Intent())
        val id = requireNotNull(state.screen.localId)
        assertEquals(0, media.starts)
        grant(id)
        grant(id)
        assertEquals(1, media.starts)
        media.started()
        assertTrue(state.screen.sending)
        controller.stopScreen(callId)
        grant(id)
        assertEquals(1, media.starts)
        assertFalse(state.screen.requested)
        controller.close()
    }

    @Test fun cancelledGrantDoesNotRestartCaptureAndPeerWinDoesNotEndCall() {
        prepare()
        controller.requestScreen(callId, Intent())
        val id = requireNotNull(state.screen.localId)
        controller.stopScreen(callId)
        grant(id)
        assertEquals(0, media.starts)
        controller.requestScreen(callId, Intent())
        grant("00000000-0000-0000-0000-000000000077", "bob")
        assertFalse(state.screen.requested)
        assertNotNull(state.screen.remoteId)
        assertFalse(media.closed)
        controller.close()
    }

    @Test fun networkRecoveryKeepsSameCaptureAndSystemStopKeepsAudio() {
        prepare()
        controller.requestScreen(callId, Intent())
        grant(requireNotNull(state.screen.localId))
        media.started()
        controller.onMediaConnection(callId, 2, MediaConnectionState.Disconnected)
        assertTrue(media.paused)
        controller.onMediaConnection(callId, 2, MediaConnectionState.Connected)
        assertFalse(media.paused)
        assertEquals(1, media.starts)
        media.stopped(null)
        assertFalse(state.screen.requested)
        assertFalse(media.closed)
        assertFalse(state.requested)
        controller.close()
    }

    @Test fun screenSizePreservesOrientationAndBounds() {
        assertEquals(720 to 1280, screenCaptureSize(1080, 1920))
        assertEquals(1280 to 720, screenCaptureSize(1920, 1080))
        assertEquals(600 to 800, screenCaptureSize(600, 800))
    }

    private class ScreenMedia : MediaSession, ScreenMediaSession {
        var starts = 0
        var paused = false
        var closed = false
        var started: () -> Unit = {}
        var stopped: (String?) -> Unit = {}
        override fun startScreen(permission: Intent, onStarted: () -> Unit, onStopped: (String?) -> Unit) {
            starts++; started = onStarted; stopped = onStopped
        }
        override fun stopScreen(onStopped: () -> Unit) = onStopped()
        override fun setScreenPaused(paused: Boolean) { this.paused = paused }
        override suspend fun createOffer() = "offer"
        override suspend fun acceptOffer(sdp: String) = "answer"
        override suspend fun setAnswer(sdp: String) = Unit
        override suspend fun addIceCandidate(candidate: IceCandidateData) = Unit
        override suspend fun removeIceCandidates(candidates: List<IceCandidateData>) = Unit
        override suspend fun restartIce() = "offer"
        override suspend fun updateIceServers(servers: List<IceServerData>) = Unit
        override fun beginRemoteDescription() = Unit
        override fun onNetworkChanged() = Unit
        override fun setMuted(muted: Boolean) = Unit
        override fun setActive(active: Boolean) = Unit
        override fun getStats(onResult: (CallStats) -> Unit) = Unit
        override suspend fun close() { closed = true }
    }
}
