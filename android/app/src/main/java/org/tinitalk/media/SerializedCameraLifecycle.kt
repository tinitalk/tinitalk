package org.tinitalk.media

import org.tinitalk.call.CameraFacing
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal fun interface CameraTaskQueue {
    fun execute(task: () -> Unit)
}

/** Rejects new work after close and absorbs an executor shutdown race. Accepted work still runs. */
internal class CloseableCameraTaskQueue(
    private val delegate: CameraTaskQueue,
) : CameraTaskQueue, AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun execute(task: () -> Unit) {
        if (closed.get()) return
        try {
            delegate.execute(task)
        } catch (_: RejectedExecutionException) {
            // close() may race between the closed check and Executor.execute(). Late native work is stale.
        }
    }

    override fun close() {
        closed.set(true)
    }
}

internal data class CameraAttemptCandidate(
    val id: String,
    val facing: CameraFacing,
    val oppositeTarget: String?,
)

internal interface CameraAttemptProvider<Track> {
    fun candidates(): List<CameraAttemptCandidate>
    fun create(candidate: CameraAttemptCandidate, events: CameraAttemptEvents): CameraAttempt<Track>
}

internal interface CameraAttempt<out Track> {
    var facing: CameraFacing
    val localTrack: Track
    val opening: Boolean
    fun oppositeFacingDevice(): String?
    fun start()
    fun detach(): CameraAttemptRelease
    fun stop()
    fun dispose()
    fun switchCamera(targetDevice: String, events: CameraSwitchEvents)
}

internal interface CameraAttemptEvents {
    fun onFirstFrame()
    fun onFailure(message: String)
}

internal interface CameraSwitchEvents {
    fun onDone(facing: CameraFacing)
    fun onFailure(message: String) = Unit
}

internal data class CameraAttemptRelease(
    val failure: String? = null,
    val requiresPeerClosed: Boolean = false,
)

internal class CameraAttemptCreationException(
    message: String,
    val fatal: Boolean,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

internal data class CameraLifecycleCallbacks<Track>(
    val onLocalTrackChanged: (Track?) -> Unit = {},
    val onCaptureStarted: (CameraFacing) -> Unit = {},
    val onCaptureInvalidated: () -> Unit = {},
    val onCaptureStopped: () -> Unit = {},
    val onFacingChanged: (CameraFacing) -> Unit = {},
    val onFailure: (String) -> Unit = {},
)

/**
 * Serializes decisions on [controlQueue], while all potentially blocking WebRTC stops run on
 * [blockingQueue]. An opening attempt is detached and orphaned immediately: logical completion
 * never waits for CameraCapturer.sessionOpening, and native disposal waits until stop really exits.
 */
internal class SerializedCameraLifecycle<Track>(
    private val controlQueue: CameraTaskQueue,
    private val blockingQueue: CameraTaskQueue,
    private val mainQueue: CameraTaskQueue,
    private val provider: CameraAttemptProvider<Track>,
    private val callbacks: CameraLifecycleCallbacks<Track>,
) {
    private val generation = AtomicLong()
    private val wantedGeneration = AtomicLong()
    private val peerClosed = AtomicBoolean(false)
    private val retirementLock = Any()
    private val retirements = mutableListOf<AttemptRetirement>()
    private val allReleasedCallbacks = mutableListOf<() -> Unit>()
    private var candidates = emptyList<CameraAttemptCandidate>()
    private var candidateIndex = 0
    private var current: CameraAttempt<Track>? = null
    private var firstFrameSeen = false
    private var switchPending = false

    fun start() {
        val nextGeneration = generation.incrementAndGet()
        wantedGeneration.set(nextGeneration)
        controlQueue.execute { begin(nextGeneration) }
    }

    fun pause(
        afterDetached: () -> Unit = {},
        afterReleased: () -> Unit = {},
    ) = invalidateAndRetire(afterDetached, afterReleased)

    fun stop(
        afterDetached: () -> Unit = {},
        afterReleased: () -> Unit = {},
    ) = invalidateAndRetire(afterDetached, afterReleased)

    fun close(
        afterDetached: () -> Unit,
        afterReleased: () -> Unit,
    ) = invalidateAndRetire(afterDetached, afterReleased)

    fun switchCamera() {
        val expectedGeneration = generation.get()
        controlQueue.execute {
            val attempt = current
                ?.takeIf { isWanted(expectedGeneration) && firstFrameSeen && !switchPending }
                ?: return@execute
            val target = attempt.oppositeFacingDevice() ?: return@execute
            switchPending = true
            runCatching {
                attempt.switchCamera(
                    target,
                    object : CameraSwitchEvents {
                        override fun onDone(facing: CameraFacing) {
                            controlQueue.execute { switchDone(expectedGeneration, attempt, facing) }
                        }

                        override fun onFailure(message: String) {
                            controlQueue.execute { switchFailed(expectedGeneration, attempt, message) }
                        }
                    },
                )
            }.onFailure {
                switchFailed(expectedGeneration, attempt, it.message ?: "camera switch failed")
            }
        }
    }

    fun disposeAfterPeerClosed(afterAllReleased: () -> Unit = {}) {
        peerClosed.set(true)
        val snapshot = synchronized(retirementLock) {
            if (retirements.isEmpty()) emptyList() else {
                allReleasedCallbacks += afterAllReleased
                retirements.toList()
            }
        }
        if (snapshot.isEmpty()) {
            afterAllReleased()
        } else {
            snapshot.forEach { retirement -> retirement.peerDidClose() }
        }
    }

    private fun begin(expectedGeneration: Long) {
        if (!isWanted(expectedGeneration)) return
        retireCurrent { release ->
            if (!isWanted(expectedGeneration)) return@retireCurrent
            if (release.failure != null) {
                terminalFailure(expectedGeneration, release.failure)
                return@retireCurrent
            }
            candidates = runCatching(provider::candidates).getOrElse {
                terminalFailure(expectedGeneration, it.message ?: "failed to enumerate cameras")
                return@retireCurrent
            }
            candidateIndex = 0
            tryNext(expectedGeneration, null)
        }
    }

    private fun tryNext(expectedGeneration: Long, previousFailure: String?) {
        if (!isWanted(expectedGeneration)) return
        if (candidateIndex >= candidates.size) {
            terminalFailure(expectedGeneration, previousFailure ?: "no camera is available")
            return
        }
        val candidate = candidates[candidateIndex++]
        var created: CameraAttempt<Track>? = null
        val events = object : CameraAttemptEvents {
            override fun onFirstFrame() {
                val attempt = created ?: return
                controlQueue.execute { firstFrame(expectedGeneration, attempt) }
            }

            override fun onFailure(message: String) {
                val attempt = created ?: return
                controlQueue.execute { attemptFailed(expectedGeneration, attempt, message) }
            }
        }
        try {
            val attempt = provider.create(candidate, events)
            created = attempt
            current = attempt
            firstFrameSeen = false
            switchPending = false
            postMain(expectedGeneration) { callbacks.onLocalTrackChanged(attempt.localTrack) }
            attempt.start()
        } catch (failure: Throwable) {
            created?.let { attempt ->
                current = attempt
                clearTrackThen(expectedGeneration) {
                    if (!isWanted(expectedGeneration) || current !== attempt) return@clearTrackThen
                    retireCurrent { release ->
                        when {
                            !isWanted(expectedGeneration) -> Unit
                            release.failure != null -> terminalFailure(expectedGeneration, release.failure)
                            (failure as? CameraAttemptCreationException)?.fatal == true -> {
                                terminalFailure(expectedGeneration, failure.message ?: "camera start failed")
                            }
                            else -> tryNext(expectedGeneration, failure.message ?: "camera start failed")
                        }
                    }
                }
                return
            }
            if ((failure as? CameraAttemptCreationException)?.fatal == true) {
                terminalFailure(expectedGeneration, failure.message ?: "camera start failed")
            } else {
                tryNext(expectedGeneration, failure.message ?: "camera start failed")
            }
        }
    }

    private fun firstFrame(expectedGeneration: Long, attempt: CameraAttempt<Track>) {
        if (!isWanted(expectedGeneration) || current !== attempt || firstFrameSeen) return
        firstFrameSeen = true
        postMain(expectedGeneration) { callbacks.onCaptureStarted(attempt.facing) }
    }

    private fun attemptFailed(expectedGeneration: Long, attempt: CameraAttempt<Track>, message: String) {
        if (!isWanted(expectedGeneration) || current !== attempt) return
        val hadFirstFrame = firstFrameSeen
        clearTrackThen(expectedGeneration) {
            if (!isWanted(expectedGeneration) || current !== attempt) return@clearTrackThen
            retireCurrent { release ->
                when {
                    !isWanted(expectedGeneration) -> Unit
                    release.failure != null -> terminalFailure(expectedGeneration, release.failure)
                    !hadFirstFrame -> tryNext(expectedGeneration, message)
                    else -> terminalFailure(expectedGeneration, message)
                }
            }
        }
    }

    private fun switchDone(
        expectedGeneration: Long,
        attempt: CameraAttempt<Track>,
        facing: CameraFacing,
    ) {
        if (!isWanted(expectedGeneration) || current !== attempt || !switchPending) return
        switchPending = false
        attempt.facing = facing
        postMain(expectedGeneration) { callbacks.onFacingChanged(facing) }
    }

    private fun switchFailed(expectedGeneration: Long, attempt: CameraAttempt<Track>, message: String) {
        if (!isWanted(expectedGeneration) || current !== attempt || !switchPending) return
        switchPending = false
        clearTrackThen(expectedGeneration) {
            if (!isWanted(expectedGeneration) || current !== attempt) return@clearTrackThen
            retireCurrent { release -> terminalFailure(expectedGeneration, release.failure ?: message) }
        }
    }

    private fun invalidateAndRetire(
        afterDetached: () -> Unit,
        afterReleased: () -> Unit,
    ) {
        val nextGeneration = generation.incrementAndGet()
        wantedGeneration.set(0L)
        mainQueue.execute {
            if (generation.get() == nextGeneration) {
                callbacks.onCaptureInvalidated()
                callbacks.onLocalTrackChanged(null)
            }
        }
        controlQueue.execute {
            if (generation.get() != nextGeneration) {
                afterDetached()
                afterAllRetirements(afterReleased)
                return@execute
            }
            candidates = emptyList()
            candidateIndex = 0
            retireCurrent { release ->
                mainQueue.execute {
                    if (generation.get() == nextGeneration) {
                        callbacks.onCaptureStopped()
                        release.failure?.let(callbacks.onFailure)
                    }
                }
                afterDetached()
                afterAllRetirements(afterReleased)
            }
        }
    }

    private fun terminalFailure(expectedGeneration: Long, message: String) {
        if (!generation.compareAndSet(expectedGeneration, expectedGeneration + 1)) return
        wantedGeneration.compareAndSet(expectedGeneration, 0L)
        val terminalGeneration = expectedGeneration + 1
        mainQueue.execute {
            if (generation.get() == terminalGeneration) {
                callbacks.onCaptureInvalidated()
                callbacks.onLocalTrackChanged(null)
                callbacks.onCaptureStopped()
                callbacks.onFailure(message)
            }
        }
    }

    private fun retireCurrent(afterDetached: (CameraAttemptRelease) -> Unit) {
        val attempt = current
        if (attempt == null) {
            afterDetached(CameraAttemptRelease())
            return
        }
        current = null
        firstFrameSeen = false
        switchPending = false
        val release = runCatching(attempt::detach).getOrElse {
            CameraAttemptRelease(
                failure = it.message ?: "failed to detach camera",
                requiresPeerClosed = true,
            )
        }
        val retirement = AttemptRetirement(attempt, release.requiresPeerClosed)
        synchronized(retirementLock) { retirements += retirement }
        afterDetached(release)
        blockingQueue.execute {
            retirement.stopFinished(runCatching(attempt::stop).isSuccess)
        }
    }

    private fun afterAllRetirements(callback: () -> Unit) {
        val released = synchronized(retirementLock) {
            if (retirements.isEmpty()) {
                true
            } else {
                allReleasedCallbacks += callback
                false
            }
        }
        if (released) callback()
    }

    private fun postMain(expectedGeneration: Long, action: () -> Unit) {
        mainQueue.execute {
            if (generation.get() == expectedGeneration) action()
        }
    }

    private fun clearTrackThen(expectedGeneration: Long, continuation: () -> Unit) {
        mainQueue.execute {
            if (generation.get() != expectedGeneration) return@execute
            callbacks.onCaptureInvalidated()
            callbacks.onLocalTrackChanged(null)
            controlQueue.execute(continuation)
        }
    }

    private fun isWanted(expectedGeneration: Long): Boolean =
        generation.get() == expectedGeneration && wantedGeneration.get() == expectedGeneration

    private inner class AttemptRetirement(
        private val attempt: CameraAttempt<Track>,
        private val requiresPeerClosed: Boolean,
    ) {
        private val stopSucceeded = AtomicBoolean(false)
        private val disposed = AtomicBoolean(false)

        fun stopFinished(succeeded: Boolean) {
            if (succeeded) stopSucceeded.set(true)
            disposeIfSafe()
        }

        fun peerDidClose() = disposeIfSafe()

        private fun disposeIfSafe() {
            if (!stopSucceeded.get()) return
            if (requiresPeerClosed && !peerClosed.get()) return
            if (!disposed.compareAndSet(false, true)) return
            runCatching(attempt::dispose)
            val callbacks = synchronized(retirementLock) {
                retirements.remove(this)
                if (retirements.isEmpty()) {
                    val pending = allReleasedCallbacks.toList()
                    allReleasedCallbacks.clear()
                    pending
                } else {
                    emptyList()
                }
            }
            callbacks.forEach { it() }
        }
    }
}
