package org.tinitalk.call

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.tinitalk.data.signal.SignalEvent
import org.tinitalk.media.CallStats
import org.tinitalk.media.CameraAttempt
import org.tinitalk.media.CameraAttemptCandidate
import org.tinitalk.media.CameraAttemptEvents
import org.tinitalk.media.CameraAttemptProvider
import org.tinitalk.media.CameraAttemptRelease
import org.tinitalk.media.CameraLifecycleCallbacks
import org.tinitalk.media.CameraMediaSession
import org.tinitalk.media.CameraSwitchEvents
import org.tinitalk.media.CameraTaskQueue
import org.tinitalk.media.IceCandidateData
import org.tinitalk.media.IceServerData
import org.tinitalk.media.MediaSession
import org.tinitalk.media.MediaConnectionState
import org.tinitalk.media.SerializedCameraLifecycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class ForegroundCallVideoControllerTest {
    @Test
    fun routesRequestedVideoThroughTheCurrentCallSessionAndLifecycle() {
        val session = FakeCameraMediaSession()
        val states = mutableListOf<CallVideoState<*>>()
        var factoryCallId: String? = null
        val controller = ForegroundCallController(
            signal = NoopSignalClient,
            mediaFactory = { createdCallId, _, _, _, _, _ ->
                factoryCallId = createdCallId
                session
            },
            ids = FixedIds,
            onVideoStateChanged = states::add,
        )
        configureVideoSession(controller)

        controller.setCameraForeground(CurrentCall, foreground = true, permissionGranted = true)
        controller.setCameraRequested(CurrentCall, requested = true)
        controller.onCameraCaptureStarted(CurrentCall, CameraFacing.Front)
        controller.setCameraForeground(CurrentCall, foreground = false, permissionGranted = true)
        controller.setCameraForeground(CurrentCall, foreground = true, permissionGranted = true)
        controller.setCameraRequested(CurrentCall, requested = false)

        assertEquals(CurrentCall, factoryCallId)
        assertEquals(2, session.starts)
        assertEquals(1, session.pauses)
        assertEquals(1, session.stops)
        assertFalse(states.last().requested)
        assertFalse(states.last().sending)
    }

    @Test
    fun cameraStartupFailureDoesNotCloseTheAudioSession() {
        val session = FakeCameraMediaSession(failStart = true)
        val states = mutableListOf<CallVideoState<*>>()
        val controller = controller(session, states::add)
        configureVideoSession(controller)

        controller.setCameraForeground(CurrentCall, foreground = true, permissionGranted = true)
        controller.setCameraRequested(CurrentCall, requested = true)

        assertFalse(session.closed)
        assertTrue(states.last().requested)
        assertFalse(states.last().sending)
        assertEquals("camera start failed", states.last().failure)
    }

    @Test
    fun staleCameraCallbackCannotPromoteAReplacementCallToSending() {
        val session = FakeCameraMediaSession()
        val states = mutableListOf<CallVideoState<*>>()
        val controller = controller(session, states::add)
        configureVideoSession(controller)
        controller.onSignalEvent(
            CallSnapshot(CallPhase.Active, ReplacementCall, 2),
            event(ReplacementCall, "rtc.config", videoConfig()),
        )

        controller.onCameraCaptureStarted(CurrentCall, CameraFacing.Back)

        assertEquals(ReplacementCall, states.last().callId)
        assertFalse(states.last().sending)
        assertEquals(CameraFacing.Front, states.last().facing)
    }

    @Test
    fun authoritativeVideoRevocationStopsCameraWithoutClosingAudioSession() {
        val session = FakeCameraMediaSession(deferRelease = true)
        val states = mutableListOf<CallVideoState<*>>()
        val controller = controller(session, states::add)
        configureVideoSession(controller)
        controller.setCameraForeground(CurrentCall, foreground = true, permissionGranted = true)
        controller.setCameraRequested(CurrentCall, requested = true)
        val stopsBeforeRevocation = session.stops

        controller.onSignalEvent(
            CallSnapshot(CallPhase.Active, CurrentCall, 2),
            event(CurrentCall, "rtc.config", JsonObject().apply { add("ice_servers", JsonArray()) }),
        )

        assertEquals(stopsBeforeRevocation + 1, session.stops)
        assertFalse(session.closed)
        assertFalse(states.last().allowed)
        assertFalse(states.last().requested)
        assertEquals(CameraFacing.Front, states.last().facing)
        session.completeCameraRelease()
    }

    @Test
    fun replacementCallConfigStopsOldCameraBeforePublishingReplacementState() {
        val session = FakeCameraMediaSession(deferRelease = true)
        val observations = mutableListOf<String>()
        val controller = controller(session) { state -> observations += "state:${state.callId}" }
        session.onStop = { observations += "stop" }
        configureVideoSession(controller)
        controller.setCameraForeground(CurrentCall, foreground = true, permissionGranted = true)
        controller.setCameraRequested(CurrentCall, requested = true)
        observations.clear()

        controller.onSignalEvent(
            CallSnapshot(CallPhase.Active, ReplacementCall, 2),
            event(ReplacementCall, "rtc.config", videoConfig()),
        )

        assertEquals(listOf("stop", "state:$ReplacementCall"), observations)
        assertFalse(session.closed)
        session.completeCameraRelease()
    }

    @Test
    fun callCloseClearsStateButRetainsLeaseAndServiceCompletionUntilPhysicalRelease() {
        val session = BlockingCameraMediaSession()
        val states = mutableListOf<CallVideoState<*>>()
        val prepared = AtomicLong()
        val released = AtomicLong()
        val leaseReleased = CountDownLatch(1)
        val serviceCompleted = CountDownLatch(1)
        val serviceCompletions = AtomicInteger()
        val controller = ForegroundCallController(
            signal = NoopSignalClient,
            mediaFactory = { _, _, _, _, _, _ -> session },
            ids = FixedIds,
            onVideoStateChanged = states::add,
            prepareCameraStart = { _, lease -> prepared.set(lease); true },
            onCameraLeaseReleased = { lease ->
                released.set(lease)
                leaseReleased.countDown()
            },
        )
        configureVideoSession(controller)
        controller.setCameraForeground(CurrentCall, foreground = true, permissionGranted = true)
        controller.setCameraRequested(CurrentCall, requested = true)
        controller.close {
            serviceCompletions.incrementAndGet()
            serviceCompleted.countDown()
        }

        assertTrue(session.stopEntered.await(1, TimeUnit.SECONDS))
        assertNull(states.last().callId)
        assertTrue(session.closed)
        assertEquals(0L, released.get())
        assertEquals(0, serviceCompletions.get())

        session.releaseStopAndAwait()

        assertTrue(leaseReleased.await(1, TimeUnit.SECONDS))
        assertTrue(serviceCompleted.await(1, TimeUnit.SECONDS))
        assertEquals(prepared.get(), released.get())
        assertEquals(1, serviceCompletions.get())
    }

    @Test
    fun callEndClosesMediaAfterLogicalCameraDetach() {
        val session = FakeCameraMediaSession(deferRelease = true)
        val controller = controller(session) {}
        configureVideoSession(controller)
        controller.setCameraForeground(CurrentCall, foreground = true, permissionGranted = true)
        controller.setCameraRequested(CurrentCall, requested = true)

        controller.onSignalEvent(
            CallSnapshot(CallPhase.Ended, CurrentCall, 2),
            event(CurrentCall, "call.end", JsonObject()),
        )

        assertTrue(session.closed)
        session.completeCameraRelease()
    }

    @Test
    fun foregroundLeaseIsReleasedByItsStopCompletionBeforeResumeGetsANewLease() {
        val session = FakeCameraMediaSession(deferRelease = true)
        val prepared = mutableListOf<Long>()
        val released = mutableListOf<Long>()
        val controller = ForegroundCallController(
            signal = NoopSignalClient,
            mediaFactory = { _, _, _, _, _, _ -> session },
            ids = FixedIds,
            prepareCameraStart = { _, lease -> prepared += lease; true },
            onCameraLeaseReleased = released::add,
        )
        configureVideoSession(controller)
        controller.setCameraForeground(CurrentCall, foreground = true, permissionGranted = true)
        controller.setCameraRequested(CurrentCall, requested = true)

        controller.setCameraForeground(CurrentCall, foreground = false, permissionGranted = true)
        controller.setCameraForeground(CurrentCall, foreground = true, permissionGranted = true)

        assertEquals(1, session.starts)
        assertTrue(released.isEmpty())
        session.completeCameraRelease()
        assertEquals(listOf(prepared.first()), released)
        assertEquals(2, session.starts)
        assertTrue(prepared[1] > prepared[0])
    }

    @Test
    fun reentrantPhysicalReleaseDuringCloseCompletesLeaseAndServiceOnce() {
        val session = FakeCameraMediaSession(deferRelease = true, releaseOnClose = true)
        val prepared = mutableListOf<Long>()
        val released = mutableListOf<Long>()
        var serviceCompletions = 0
        val controller = ForegroundCallController(
            signal = NoopSignalClient,
            mediaFactory = { _, _, _, _, _, _ -> session },
            ids = FixedIds,
            prepareCameraStart = { _, lease -> prepared += lease; true },
            onCameraLeaseReleased = released::add,
        )
        configureVideoSession(controller)
        controller.setCameraForeground(CurrentCall, foreground = true, permissionGranted = true)
        controller.setCameraRequested(CurrentCall, requested = true)
        controller.setCameraForeground(CurrentCall, foreground = false, permissionGranted = true)

        controller.close { serviceCompletions++ }

        assertTrue(session.closed)
        assertEquals(listOf(prepared.single()), released)
        assertEquals(1, serviceCompletions)
    }

    @Test
    fun replacementCameraWaitsForPriorPhysicalReleaseWhileReplacementAudioStaysActive() {
        val oldSession = FakeCameraMediaSession(deferRelease = true)
        val replacementSession = FakeCameraMediaSession()
        val sessions = mapOf(CurrentCall to oldSession, ReplacementCall to replacementSession)
        val controller = ForegroundCallController(
            signal = NoopSignalClient,
            mediaFactory = { callId, _, _, _, _, _ -> requireNotNull(sessions[callId]) },
            ids = FixedIds,
        )
        configureVideoSession(controller)
        controller.setCameraForeground(CurrentCall, foreground = true, permissionGranted = true)
        controller.setCameraRequested(CurrentCall, requested = true)

        val replacement = CallSnapshot(CallPhase.Active, ReplacementCall, 2)
        controller.onSignalEvent(replacement, event(ReplacementCall, "rtc.config", videoConfig()))
        controller.onSignalEvent(
            replacement,
            event(ReplacementCall, "rtc.offer", JsonObject().apply { addProperty("sdp", "replacement-offer") }),
        )
        controller.setCameraForeground(ReplacementCall, foreground = true, permissionGranted = true)
        controller.setCameraRequested(ReplacementCall, requested = true)

        assertFalse(replacementSession.closed)
        assertEquals(0, replacementSession.starts)

        oldSession.completeCameraRelease()

        assertEquals(1, replacementSession.starts)
    }

    @Test
    fun foregroundCameraTypeIsPreparedBeforeCaptureSubmission() {
        val session = FakeCameraMediaSession()
        val order = mutableListOf<String>()
        session.onStart = { order += "capture" }
        val controller = ForegroundCallController(
            signal = NoopSignalClient,
            mediaFactory = { _, _, _, _, _, _ -> session },
            ids = FixedIds,
            prepareCameraStart = { _, _ ->
                order += "foreground"
                true
            },
        )
        configureVideoSession(controller)
        controller.setCameraForeground(CurrentCall, foreground = true, permissionGranted = true)

        controller.setCameraRequested(CurrentCall, requested = true)

        assertEquals(listOf("foreground", "capture"), order)
    }

    @Test
    fun sustainedPoorNetworkPausesVideoButPreservesRequestAndRecoversAfterTwoGoodSamples() {
        val session = FakeCameraMediaSession()
        val states = mutableListOf<CallVideoState<*>>()
        val controller = controller(session, states::add)
        configureVideoSession(controller)
        prepareHealthyTransport(controller)
        controller.setCameraForeground(CurrentCall, foreground = true, permissionGranted = true)
        controller.setCameraRequested(CurrentCall, requested = true)

        repeat(2) { controller.onNetworkQualitySample(CurrentCall, epoch = 1, NetworkQuality.Poor) }
        assertEquals(0, session.pauses)
        controller.onNetworkQualitySample(CurrentCall, epoch = 1, NetworkQuality.Poor)

        assertEquals(1, session.pauses)
        assertTrue(states.last().requested)
        assertTrue(states.last().networkGated)
        assertFalse(session.closed)

        controller.onNetworkQualitySample(CurrentCall, epoch = 1, NetworkQuality.Good)
        assertEquals(1, session.starts)
        controller.onNetworkQualitySample(CurrentCall, epoch = 1, NetworkQuality.Good)

        assertFalse(states.last().networkGated)
        assertEquals(2, session.starts)
    }

    @Test
    fun reconnectRecoverySurvivesDuplicateConnectedAndWaitsForPhysicalRelease() {
        val session = FakeCameraMediaSession(deferRelease = true)
        val states = mutableListOf<CallVideoState<*>>()
        val controller = controller(session, states::add)
        configureVideoSession(controller)
        prepareHealthyTransport(controller)
        controller.setCameraForeground(CurrentCall, foreground = true, permissionGranted = true)
        controller.setCameraRequested(CurrentCall, requested = true)

        controller.onMediaConnection(CurrentCall, epoch = 2, MediaConnectionState.Disconnected)
        controller.onMediaConnection(CurrentCall, epoch = 2, MediaConnectionState.Connected)
        controller.onNetworkQualitySample(CurrentCall, epoch = 2, NetworkQuality.Good)
        controller.onMediaConnection(CurrentCall, epoch = 2, MediaConnectionState.Connected)
        controller.onNetworkQualitySample(CurrentCall, epoch = 2, NetworkQuality.Good)

        assertFalse(states.last().networkGated)
        assertEquals(1, session.starts)
        session.completeCameraRelease()
        assertEquals(2, session.starts)
    }

    @Test
    fun userOffHidesGateStateButReRequestCannotBypassPoorNetwork() {
        val session = FakeCameraMediaSession()
        val states = mutableListOf<CallVideoState<*>>()
        val controller = controller(session, states::add)
        configureVideoSession(controller)
        prepareHealthyTransport(controller)
        controller.setCameraForeground(CurrentCall, foreground = true, permissionGranted = true)
        controller.setCameraRequested(CurrentCall, requested = true)
        repeat(3) { controller.onNetworkQualitySample(CurrentCall, epoch = 1, NetworkQuality.Poor) }

        controller.setCameraRequested(CurrentCall, requested = false)
        assertFalse(states.last().requested)
        assertTrue(states.last().networkGated)
        val starts = session.starts

        controller.setCameraRequested(CurrentCall, requested = true)

        assertTrue(states.last().requested)
        assertTrue(states.last().networkGated)
        assertEquals(starts, session.starts)
        assertFalse(session.closed)
    }

    @Test
    fun replacementCallResetsGateAndRejectsStaleNetworkEvents() {
        val session = FakeCameraMediaSession()
        val states = mutableListOf<CallVideoState<*>>()
        val controller = controller(session, states::add)
        configureVideoSession(controller)
        prepareHealthyTransport(controller)
        repeat(3) { controller.onNetworkQualitySample(CurrentCall, epoch = 1, NetworkQuality.Poor) }
        assertTrue(states.last().networkGated)

        controller.onSignalEvent(
            CallSnapshot(CallPhase.Active, ReplacementCall, 2),
            event(ReplacementCall, "rtc.config", videoConfig()),
        )
        controller.onMediaConnection(CurrentCall, epoch = 99, MediaConnectionState.Disconnected)

        assertEquals(ReplacementCall, states.last().callId)
        assertFalse(states.last().networkGated)
    }

    @Test
    fun callCloseResetsABlockedNetworkGate() {
        val session = FakeCameraMediaSession()
        val states = mutableListOf<CallVideoState<*>>()
        val controller = controller(session, states::add)
        configureVideoSession(controller)
        controller.onMediaConnection(CurrentCall, epoch = 1, MediaConnectionState.Connected)
        repeat(3) { controller.onNetworkQualitySample(CurrentCall, epoch = 1, NetworkQuality.Poor) }
        assertTrue(states.last().networkGated)

        controller.close()

        assertFalse(states.last().networkGated)
        assertNull(states.last().callId)
    }

    @Test
    fun gateAndRecoveryBeforeFirstCameraStartDoNotCreateAPendingRetirement() {
        val session = FakeCameraMediaSession()
        val states = mutableListOf<CallVideoState<*>>()
        val controller = controller(session, states::add)
        configureVideoSession(controller)
        controller.onMediaConnection(CurrentCall, epoch = 1, MediaConnectionState.Connected)
        controller.setCameraRequested(CurrentCall, requested = true)

        repeat(3) { controller.onNetworkQualitySample(CurrentCall, epoch = 1, NetworkQuality.Poor) }
        controller.setCameraForeground(CurrentCall, foreground = true, permissionGranted = true)

        assertTrue(states.last().networkGated)
        assertEquals(0, session.starts)
        assertEquals(0, session.pauses)

        repeat(2) { controller.onNetworkQualitySample(CurrentCall, epoch = 1, NetworkQuality.Good) }

        assertFalse(states.last().networkGated)
        assertEquals(1, session.starts)
        assertEquals(0, session.pauses)
    }

    private fun controller(
        session: FakeCameraMediaSession,
        onState: (CallVideoState<*>) -> Unit,
    ) = ForegroundCallController(
        signal = NoopSignalClient,
        mediaFactory = { _, _, _, _, _, _ -> session },
        ids = FixedIds,
        onVideoStateChanged = onState,
    )

    private fun configureVideoSession(controller: ForegroundCallController) {
        val snapshot = CallSnapshot(CallPhase.Active, CurrentCall, 1)
        controller.onSignalEvent(snapshot, event(CurrentCall, "rtc.config", videoConfig()))
        controller.onSignalEvent(
            snapshot,
            event(CurrentCall, "rtc.offer", JsonObject().apply { addProperty("sdp", "remote-offer") }),
        )
    }

    private fun prepareHealthyTransport(controller: ForegroundCallController) {
        controller.onMediaConnection(CurrentCall, epoch = 1, MediaConnectionState.Connected)
        controller.onNetworkQualitySample(CurrentCall, epoch = 1, NetworkQuality.Good)
        controller.onNetworkQualitySample(CurrentCall, epoch = 1, NetworkQuality.Good)
    }

    private fun videoConfig() = JsonObject().apply {
        add("ice_servers", JsonArray())
        addProperty("video_allowed", true)
    }

    private fun event(callId: String, type: String, payload: JsonObject) =
        SignalEvent("event-$callId-$type", callId, type, 1L, payload)

    private class FakeCameraMediaSession(
        private val failStart: Boolean = false,
        private val deferRelease: Boolean = false,
        private val releaseOnClose: Boolean = false,
    ) : MediaSession, CameraMediaSession {
        var starts = 0
        var pauses = 0
        var stops = 0
        var closed = false
        var onStart: () -> Unit = {}
        var onStop: () -> Unit = {}
        private val releaseCompletions = ArrayDeque<() -> Unit>()

        override fun startCamera() {
            starts++
            onStart()
            if (failStart) error("camera start failed")
        }

        override fun pauseCamera(onDetached: () -> Unit, onReleased: () -> Unit) {
            pauses++
            completeOrDefer(onDetached, onReleased)
        }

        override fun stopCamera(onDetached: () -> Unit, onReleased: () -> Unit) {
            stops++
            onStop()
            completeOrDefer(onDetached, onReleased)
        }

        fun completeCameraRelease() = releaseCompletions.removeFirst().invoke()

        private fun completeOrDefer(onDetached: () -> Unit, onReleased: () -> Unit) {
            onDetached()
            if (deferRelease) releaseCompletions.addLast(onReleased) else onReleased()
        }

        override fun switchCamera() = Unit
        override suspend fun createOffer(): String = "offer"
        override suspend fun acceptOffer(sdp: String): String = "answer"
        override suspend fun setAnswer(sdp: String) = Unit
        override suspend fun addIceCandidate(candidate: IceCandidateData) = Unit
        override suspend fun removeIceCandidates(candidates: List<IceCandidateData>) = Unit
        override suspend fun restartIce(): String = "offer"
        override suspend fun updateIceServers(servers: List<IceServerData>) = Unit
        override fun beginRemoteDescription() = Unit
        override fun onNetworkChanged() = Unit
        override fun setMuted(muted: Boolean) = Unit
        override fun setActive(active: Boolean) = Unit
        override fun getStats(onResult: (CallStats) -> Unit) = Unit
        override suspend fun close() {
            closed = true
            if (releaseOnClose) completeCameraRelease()
        }
    }

    private class BlockingCameraMediaSession : MediaSession, CameraMediaSession {
        val stopEntered = CountDownLatch(1)
        private val allowStop = CountDownLatch(1)
        private val blockingExecutor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "BlockingCameraTest").apply { isDaemon = true }
        }
        private val attempt = object : CameraAttempt<String> {
            override var facing = CameraFacing.Front
            override val localTrack = "blocking-track"
            override val opening = true
            override fun oppositeFacingDevice(): String? = null
            override fun start() = Unit
            override fun detach() = CameraAttemptRelease()
            override fun stop() {
                stopEntered.countDown()
                allowStop.await(5, TimeUnit.SECONDS)
            }
            override fun dispose() = Unit
            override fun switchCamera(targetDevice: String, events: CameraSwitchEvents) = Unit
        }
        private val lifecycle = SerializedCameraLifecycle(
            controlQueue = CameraTaskQueue { task -> task() },
            blockingQueue = CameraTaskQueue { task -> blockingExecutor.execute(task) },
            mainQueue = CameraTaskQueue { task -> task() },
            provider = object : CameraAttemptProvider<String> {
                override fun candidates() = listOf(
                    CameraAttemptCandidate("front", CameraFacing.Front, null),
                )
                override fun create(candidate: CameraAttemptCandidate, events: CameraAttemptEvents) = attempt
            },
            callbacks = CameraLifecycleCallbacks(),
        )
        var closed = false

        override fun startCamera() = lifecycle.start()
        override fun pauseCamera(onDetached: () -> Unit, onReleased: () -> Unit) =
            lifecycle.pause(onDetached, onReleased)
        override fun stopCamera(onDetached: () -> Unit, onReleased: () -> Unit) =
            lifecycle.stop(onDetached, onReleased)
        override fun switchCamera() = Unit

        fun releaseStopAndAwait() {
            allowStop.countDown()
            blockingExecutor.shutdown()
            assertTrue(blockingExecutor.awaitTermination(1, TimeUnit.SECONDS))
        }

        override suspend fun createOffer(): String = "offer"
        override suspend fun acceptOffer(sdp: String): String = "answer"
        override suspend fun setAnswer(sdp: String) = Unit
        override suspend fun addIceCandidate(candidate: IceCandidateData) = Unit
        override suspend fun removeIceCandidates(candidates: List<IceCandidateData>) = Unit
        override suspend fun restartIce(): String = "offer"
        override suspend fun updateIceServers(servers: List<IceServerData>) = Unit
        override fun beginRemoteDescription() = Unit
        override fun onNetworkChanged() = Unit
        override fun setMuted(muted: Boolean) = Unit
        override fun setActive(active: Boolean) = Unit
        override fun getStats(onResult: (CallStats) -> Unit) = Unit
        override suspend fun close() {
            closed = true
            lifecycle.disposeAfterPeerClosed()
        }
    }

    private object NoopSignalClient : SignalClient {
        override fun send(event: SignalEvent, onSettled: (() -> Unit)?) = Unit
    }

    private object FixedIds : EventIds {
        override fun nextEventId(): String = "event-id"
        override fun nextCallId(): String = CurrentCall
        override fun nowMillis(): Long = 1L
    }

    private companion object {
        const val CurrentCall = "call-1"
        const val ReplacementCall = "call-2"
    }
}
