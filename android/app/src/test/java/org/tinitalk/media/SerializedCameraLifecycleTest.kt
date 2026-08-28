package org.tinitalk.media

import org.tinitalk.call.CameraFacing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SerializedCameraLifecycleTest {
    @Test
    fun openingStopCannotBlockControlQueueOrLogicalCloseCompletion() {
        val control = ManualCameraQueue()
        val stopEntered = CountDownLatch(1)
        val unblockStop = CountDownLatch(1)
        val allNativeResourcesReleased = CountDownLatch(1)
        val blocking = ThreadCameraQueue()
        val attempt = FakeCameraAttempt(
            "camera2-front",
            CameraFacing.Front,
            "back-main",
            control,
            onStop = {
                stopEntered.countDown()
                unblockStop.await(5, TimeUnit.SECONDS)
            },
        )
        val lifecycle = lifecycle(control, FakeAttemptProvider(attempt), blockingQueue = blocking)

        lifecycle.start()
        control.runNext()
        assertTrue(attempt.opening)
        var detached = false

        attempt.events.onFailure("open timeout did not settle CameraCapturer.sessionOpening")
        lifecycle.close(
            afterDetached = { detached = true },
            afterReleased = allNativeResourcesReleased::countDown,
        )
        control.runAll()

        assertTrue(stopEntered.await(1, TimeUnit.SECONDS))
        assertTrue(detached)
        var queueProgressed = false
        control.execute { queueProgressed = true }
        control.runAll()
        assertTrue(queueProgressed)
        assertFalse(attempt.disposed)
        assertEquals(1L, allNativeResourcesReleased.count)

        unblockStop.countDown()
        assertTrue(attempt.disposedLatch.await(1, TimeUnit.SECONDS))
        assertTrue(allNativeResourcesReleased.await(1, TimeUnit.SECONDS))
        blocking.close()
    }

    @Test
    fun cameraCallbackNeverReleasesOpeningCapturerInline() {
        val control = ManualCameraQueue()
        val attempt = FakeCameraAttempt("camera2-front", CameraFacing.Front, "back-main", control)
        val lifecycle = lifecycle(control, FakeAttemptProvider(attempt))
        lifecycle.start()
        control.runNext()

        attempt.events.onFailure("async open failed")

        assertEquals(0, attempt.detachCalls)
        control.runAll()
        assertEquals(1, attempt.detachCalls)
        assertTrue(attempt.detachRanOnControlQueue)
    }

    @Test
    fun asyncFailureClearsThePublishedTrackBeforeNativeDisposal() {
        val control = ManualCameraQueue()
        val order = mutableListOf<String>()
        val attempt = FakeCameraAttempt(
            "camera2-front",
            CameraFacing.Front,
            "back-main",
            control,
            onDetach = { order += "detach" },
            onDispose = { order += "dispose" },
        )
        val lifecycle = lifecycle(
            control,
            FakeAttemptProvider(attempt),
            callbacks = CameraLifecycleCallbacks(
                onLocalTrackChanged = { track -> if (track == null) order += "clear" },
            ),
        )
        lifecycle.start()
        control.runAll()

        attempt.events.onFailure("camera failed")
        control.runAll()

        assertTrue(order.indexOf("clear") < order.indexOf("detach"))
        assertTrue(order.indexOf("detach") < order.indexOf("dispose"))
    }

    @Test
    fun preFirstFrameFailuresAdvanceThroughCamera2ThenCamera1() {
        val control = ManualCameraQueue()
        val firstCamera2 = FakeCameraAttempt("camera2-front-wide", CameraFacing.Front, "back-main", control)
        val secondCamera2 = FakeCameraAttempt("camera2-front-main", CameraFacing.Front, "back-main", control)
        val camera1 = FakeCameraAttempt("camera1-front", CameraFacing.Front, "back-main", control)
        val provider = FakeAttemptProvider(firstCamera2, secondCamera2, camera1)
        val started = mutableListOf<CameraFacing>()
        val lifecycle = lifecycle(
            control,
            provider,
            callbacks = CameraLifecycleCallbacks(onCaptureStarted = started::add),
        )
        lifecycle.start()
        control.runAll()

        firstCamera2.events.onFailure("camera2 wide failed")
        control.runAll()
        secondCamera2.events.onFailure("camera2 main failed")
        control.runAll()
        camera1.events.onFirstFrame()
        control.runAll()

        assertEquals(listOf("camera2-front-wide", "camera2-front-main", "camera1-front"), provider.created)
        assertEquals(listOf(CameraFacing.Front), started)
    }

    @Test
    fun backgroundPauseCancelsPendingFallbackQueue() {
        val control = ManualCameraQueue()
        val camera2 = FakeCameraAttempt("camera2-front", CameraFacing.Front, "back-main", control)
        val camera1 = FakeCameraAttempt("camera1-front", CameraFacing.Front, "back-main", control)
        val provider = FakeAttemptProvider(camera2, camera1)
        val lifecycle = lifecycle(control, provider)
        lifecycle.start()
        control.runAll()

        camera2.events.onFailure("camera2 failed")
        lifecycle.pause()
        control.runAll()

        assertEquals(listOf("camera2-front"), provider.created)
        assertFalse(camera1.opening)
    }

    @Test
    fun rapidSwitchesSubmitOneExplicitOppositeFacingTarget() {
        val control = ManualCameraQueue()
        val attempt = FakeCameraAttempt("front-main", CameraFacing.Front, "back-main", control)
        val facing = mutableListOf<CameraFacing>()
        val lifecycle = lifecycle(
            control,
            FakeAttemptProvider(attempt),
            callbacks = CameraLifecycleCallbacks(onFacingChanged = facing::add),
        )
        lifecycle.start()
        control.runAll()
        attempt.events.onFirstFrame()
        control.runAll()

        lifecycle.switchCamera()
        lifecycle.switchCamera()
        control.runAll()

        assertEquals(listOf("back-main"), attempt.switchTargets)
        attempt.switchEvents.onDone(CameraFacing.Back)
        control.runAll()
        assertEquals(listOf(CameraFacing.Back), facing)
    }

    @Test
    fun lateNativeAndSwitchCallbacksAreNoOpsAfterControlQueueCloses() {
        val delegate = ManualCameraQueue()
        val control = CloseableCameraTaskQueue(delegate)
        val attempt = FakeCameraAttempt("front-main", CameraFacing.Front, "back-main", delegate)
        val lifecycle = SerializedCameraLifecycle(
            controlQueue = control,
            blockingQueue = ImmediateCameraQueue,
            mainQueue = ImmediateCameraQueue,
            provider = FakeAttemptProvider(attempt),
            callbacks = CameraLifecycleCallbacks(),
        )
        lifecycle.start()
        delegate.runAll()
        attempt.events.onFirstFrame()
        delegate.runAll()
        lifecycle.switchCamera()
        delegate.runAll()
        lifecycle.close(
            afterDetached = { control.close() },
            afterReleased = {},
        )
        delegate.runAll()

        attempt.events.onFirstFrame()
        attempt.events.onFailure("late error")
        attempt.switchEvents.onDone(CameraFacing.Back)
        attempt.switchEvents.onFailure("late switch error")
    }

    private fun lifecycle(
        control: ManualCameraQueue,
        provider: FakeAttemptProvider,
        callbacks: CameraLifecycleCallbacks<String> = CameraLifecycleCallbacks(),
        blockingQueue: CameraTaskQueue = ImmediateCameraQueue,
    ) = SerializedCameraLifecycle(
        controlQueue = control,
        blockingQueue = blockingQueue,
        mainQueue = ImmediateCameraQueue,
        provider = provider,
        callbacks = callbacks,
    )

    private class FakeAttemptProvider(
        vararg attempts: FakeCameraAttempt,
    ) : CameraAttemptProvider<String> {
        private val attempts = attempts.toList()
        val created = mutableListOf<String>()

        override fun candidates(): List<CameraAttemptCandidate> =
            attempts.map { CameraAttemptCandidate(it.name, it.facing, it.oppositeTarget) }

        override fun create(
            candidate: CameraAttemptCandidate,
            events: CameraAttemptEvents,
        ): CameraAttempt<String> {
            created += candidate.id
            return attempts.single { it.name == candidate.id }.also { it.events = events }
        }
    }

    private class FakeCameraAttempt(
        val name: String,
        override var facing: CameraFacing,
        val oppositeTarget: String?,
        private val queue: ManualCameraQueue,
        private val onDetach: () -> Unit = {},
        private val onStop: () -> Unit = {},
        private val onDispose: () -> Unit = {},
    ) : CameraAttempt<String> {
        lateinit var events: CameraAttemptEvents
        lateinit var switchEvents: CameraSwitchEvents
        var stopCalls = 0
        var detachCalls = 0
        var detachRanOnControlQueue = false
        var disposed = false
        val disposedLatch = CountDownLatch(1)
        val switchTargets = mutableListOf<String>()

        override val localTrack: String = "track-$name"
        override val opening: Boolean get() = openingState
        private var openingState = false
        override fun oppositeFacingDevice(): String? = oppositeTarget

        override fun start() {
            check(queue.running)
            openingState = true
        }

        override fun stop() {
            stopCalls++
            onStop()
            openingState = false
        }

        override fun detach(): CameraAttemptRelease {
            onDetach()
            detachCalls++
            detachRanOnControlQueue = queue.running
            return CameraAttemptRelease()
        }

        override fun dispose() {
            onDispose()
            disposed = true
            disposedLatch.countDown()
        }

        override fun switchCamera(targetDevice: String, events: CameraSwitchEvents) {
            check(queue.running)
            switchTargets += targetDevice
            switchEvents = events
        }
    }

    private class ManualCameraQueue : CameraTaskQueue {
        private val tasks = ArrayDeque<() -> Unit>()
        var running = false

        override fun execute(task: () -> Unit) {
            tasks.addLast(task)
        }

        fun runNext() {
            val task = tasks.removeFirst()
            running = true
            try {
                task()
            } finally {
                running = false
            }
        }

        fun runAll() {
            while (tasks.isNotEmpty()) runNext()
        }
    }

    private object ImmediateCameraQueue : CameraTaskQueue {
        override fun execute(task: () -> Unit) = task()
    }

    private class ThreadCameraQueue : CameraTaskQueue, AutoCloseable {
        private val executor = java.util.concurrent.Executors.newCachedThreadPool()
        override fun execute(task: () -> Unit) {
            executor.execute(task)
        }

        override fun close() {
            executor.shutdownNow()
        }
    }
}
