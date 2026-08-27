package org.tinitalk.call

import com.google.gson.JsonObject
import org.tinitalk.data.signal.SignalEvent
import org.tinitalk.data.signal.SignalFailure
import org.tinitalk.media.IceCandidateData
import org.tinitalk.media.IceServerData
import org.tinitalk.media.CallStats
import org.tinitalk.media.MediaSession
import org.tinitalk.media.CancellableTask
import org.tinitalk.media.ExecutorTaskScheduler
import org.tinitalk.media.TaskScheduler
import java.time.Instant
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal const val CredentialRefreshLeadMillis = 60_000L

class ForegroundCallController(
    private val signal: SignalClient,
    private val mediaFactory: (String, List<IceServerData>, (IceCandidateData) -> Unit, () -> Unit) -> MediaSession,
    private val ids: EventIds = UuidEventIds(),
    private val scheduler: TaskScheduler = ExecutorTaskScheduler(),
) {
    private var session: MediaSession? = null
    private var active = false
    private var muted = false
    private var callId: String? = null
    private var iceServers: List<IceServerData> = emptyList()
    private var configuredCallId: String? = null
    private var acceptedCallId: String? = null
    private var offerStartedCallId: String? = null
    private var restartRequestedCallId: String? = null
    private var restartRequestID: String? = null
    private var pendingRestart: SignalEvent? = null
    private var pendingRestartRequest: SignalEvent? = null
    @Volatile private var localIceGeneration: String? = null
    private var remoteIceGeneration: String? = null
    private var credentialRefreshTask: CancellableTask? = null
    private var restartRetryTask: CancellableTask? = null
    private var restartRequestRetryTask: CancellableTask? = null
    private var pendingOffer: SignalEvent? = null
    private val pendingIce = ArrayDeque<Pair<String, IceCandidateData>>()

    @Synchronized
    fun onSignalEvent(snapshot: CallSnapshot, event: SignalEvent) {
        when (event.type) {
            "call.accept" -> if (snapshot.phase == CallPhase.Active) {
                val offerer = !event.payload.has("offerer") || event.payload["offerer"].asBoolean
                acceptedCallId = event.callId.takeIf { offerer }
                if (offerer) startOfferWhenReady(event.callId)
            }
            "rtc.offer" -> {
                if (configuredCallId == event.callId) {
                    answerOffer(event)
                } else {
                    pendingOffer = event
                }
            }
            "rtc.config" -> {
                iceServers = event.payload.parseIceServers()
                configuredCallId = event.callId
                event.payload.restartID()?.let { localIceGeneration = it }
                session?.takeIf { callId == event.callId }?.let { media ->
                    runBlockingLite { media.updateIceServers(iceServers) }
                }
                if (restartRequestedCallId == event.callId && restartRequestID == event.payload.restartID()) {
                    restartRetryTask?.cancel()
                    restartRetryTask = null
                    val offer = runBlockingLite { ensureSession(event.callId).restartIce() }
                    restartRequestedCallId = null
                    restartRequestID = null
                    pendingRestart = null
                    sendSdp(event.callId, "rtc.offer", offer)
                } else {
                    startOfferWhenReady(event.callId)
                }
                scheduleCredentialRefresh(event.callId)
                pendingOffer?.takeIf { it.callId == event.callId }?.let {
                    pendingOffer = null
                    answerOffer(it)
                }
            }
            "rtc.answer" -> session?.let { media ->
                runBlockingLite { media.setAnswer(event.payload["sdp"].asString) }
            }
            "rtc.restart" -> {
                clearPendingRestartRequest(event.callId)
                remoteIceGeneration = event.id
                session?.beginRemoteDescription()
            }
            "rtc.restart.request" -> restartIce(event.callId)
            "rtc.ice" -> {
                val generation = event.payload.restartID()
                if (generation != null && generation != remoteIceGeneration) return
                val candidate = IceCandidateData(
                    sdpMid = event.payload["sdp_mid"].asString,
                    sdpMLineIndex = event.payload["sdp_mline_index"].asInt,
                    candidate = event.payload["candidate"].asString,
                )
                val media = session
                if (media != null && callId == event.callId) {
                    runBlockingLite { media.addIceCandidate(candidate) }
                } else {
                    if (pendingIce.size == SignalEvent.EVENT_BUFFER_LIMIT) pendingIce.removeFirst()
                    pendingIce.addLast(event.callId to candidate)
                }
            }
            "call.reject", "call.cancel", "call.end", "call.expire" -> close()
        }
    }

    @Synchronized
    fun setMuted(muted: Boolean) {
        this.muted = muted
        session?.setMuted(muted)
    }

    @Synchronized
    fun setActive(active: Boolean) {
        this.active = active
        session?.setActive(active)
    }

    @Synchronized
    fun getStats(onResult: (CallStats) -> Unit) {
        val current = session ?: return
        current.getStats { stats ->
            if (isCurrentSession(current)) onResult(stats)
        }
    }

    @Synchronized
    fun close() {
        credentialRefreshTask?.cancel()
        credentialRefreshTask = null
        restartRetryTask?.cancel()
        restartRetryTask = null
        restartRequestRetryTask?.cancel()
        restartRequestRetryTask = null
        scheduler.close()
        val media = session
        session = null
        callId = null
        iceServers = emptyList()
        configuredCallId = null
        acceptedCallId = null
        offerStartedCallId = null
        restartRequestedCallId = null
        restartRequestID = null
        pendingRestart = null
        pendingRestartRequest = null
        localIceGeneration = null
        remoteIceGeneration = null
        pendingOffer = null
        pendingIce.clear()
        active = false
        muted = false
        if (media != null) runBlockingLite { media.close() }
    }

    private fun startOfferWhenReady(nextCallId: String) {
        if (acceptedCallId != nextCallId || configuredCallId != nextCallId || offerStartedCallId == nextCallId) return
        offerStartedCallId = nextCallId
        val offer = runBlockingLite { ensureSession(nextCallId).createOffer() }
        sendSdp(nextCallId, "rtc.offer", offer)
    }

    private fun answerOffer(event: SignalEvent) {
        val answer = runBlockingLite { ensureSession(event.callId).acceptOffer(event.payload["sdp"].asString) }
        sendSdp(event.callId, "rtc.answer", answer)
    }

    private fun ensureSession(nextCallId: String): MediaSession {
        val current = session
        if (current != null && callId == nextCallId) return current
        if (current != null) close()
        callId = nextCallId
        val created = mediaFactory(
            nextCallId,
            iceServers,
            { candidate -> sendIce(nextCallId, candidate) },
            { restartIce(nextCallId) },
        )
        session = created
        created.setActive(active)
        created.setMuted(muted)
        val queued = pendingIce.filter { it.first == nextCallId }.map { it.second }
        pendingIce.removeAll { it.first == nextCallId }
        if (queued.isNotEmpty()) runBlockingLite {
            queued.forEach { created.addIceCandidate(it) }
        }
        return created
    }

    @Synchronized
    private fun isCurrentSession(candidate: MediaSession): Boolean = session === candidate

    @Synchronized
    fun onSignalConnected() {
        if (restartRetryTask == null) pendingRestart?.let(signal::send)
        if (restartRequestRetryTask == null) pendingRestartRequest?.let(signal::send)
    }

    @Synchronized
    fun onSignalFailure(failure: SignalFailure) {
        when (failure.code) {
            "ice_restart_rate_limited" -> {
                val restart = pendingRestart ?: return
                if (failure.callId != restart.callId || failure.eventId != restart.id) return
                val delayMillis = failure.retryAfterMillis?.coerceAtLeast(1L) ?: return
                restartRetryTask?.cancel()
                restartRetryTask = scheduler.schedule(delayMillis) {
                    synchronized(this) {
                        restartRetryTask = null
                        val pending = pendingRestart
                        if (pending?.id == restart.id && callId == restart.callId) signal.send(pending)
                    }
                }
            }
            "ice_restart_request_rate_limited" -> {
                val request = pendingRestartRequest ?: return
                if (failure.callId != request.callId || failure.eventId != request.id) return
                val delayMillis = failure.retryAfterMillis?.coerceAtLeast(1L) ?: return
                restartRequestRetryTask?.cancel()
                restartRequestRetryTask = scheduler.schedule(delayMillis) {
                    synchronized(this) {
                        restartRequestRetryTask = null
                        val pending = pendingRestartRequest
                        if (pending?.id == request.id && callId == request.callId) signal.send(pending)
                    }
                }
            }
        }
    }

    @Synchronized
    private fun restartIce(nextCallId: String) {
        if (callId != nextCallId) return
        if (offerStartedCallId != nextCallId) {
            if (pendingRestartRequest != null) return
            event(nextCallId, "rtc.restart.request", JsonObject()).also {
                pendingRestartRequest = it
                signal.send(it)
            }
            return
        }
        if (restartRequestedCallId == nextCallId) return
        val restart = event(nextCallId, "rtc.restart", JsonObject())
        restartRequestedCallId = nextCallId
        restartRequestID = restart.id
        pendingRestart = restart
        remoteIceGeneration = restart.id
        signal.send(restart)
    }

    private fun clearPendingRestartRequest(nextCallId: String) {
        if (pendingRestartRequest?.callId != nextCallId) return
        restartRequestRetryTask?.cancel()
        restartRequestRetryTask = null
        pendingRestartRequest = null
    }

    private fun scheduleCredentialRefresh(nextCallId: String) {
        credentialRefreshTask?.cancel()
        credentialRefreshTask = null
        if (acceptedCallId != nextCallId) return
        val expiresAt = iceServers.mapNotNull { it.expiresAt }.minOrNull() ?: return
        val delayMillis = (expiresAt.toEpochMilli() - ids.nowMillis() - CredentialRefreshLeadMillis).coerceAtLeast(0L)
        credentialRefreshTask = scheduler.schedule(delayMillis) { restartIce(nextCallId) }
    }

    private fun JsonObject.parseIceServers(): List<IceServerData> {
        val servers = getAsJsonArray("ice_servers") ?: return emptyList()
        return servers.mapNotNull { element ->
            val server = element.asJsonObject
            val urls = server.getAsJsonArray("urls")?.map { it.asString } ?: return@mapNotNull null
            IceServerData(
                urls = urls,
                username = server.get("username")?.asString.orEmpty(),
                password = server.get("credential")?.asString.orEmpty(),
                expiresAt = runCatching {
                    server.get("expires_at")
                        ?.takeIf { it.isJsonPrimitive }
                        ?.asString
                        ?.let { value -> Instant.parse(value) }
                }.getOrNull(),
            )
        }
    }

    private fun JsonObject.restartID(): String? =
        get("restart_id")?.takeIf { it.isJsonPrimitive }?.asString

    private fun sendSdp(callId: String, type: String, sdp: String) {
        val payload = JsonObject().apply { addProperty("sdp", sdp) }
        signal.send(event(callId, type, payload))
    }

    private fun sendIce(callId: String, candidate: IceCandidateData) {
        val payload = JsonObject().apply {
            addProperty("sdp_mid", candidate.sdpMid)
            addProperty("sdp_mline_index", candidate.sdpMLineIndex)
            addProperty("candidate", candidate.candidate)
            localIceGeneration?.let { addProperty("restart_id", it) }
        }
        signal.send(event(callId, "rtc.ice", payload))
    }

    private fun event(callId: String, type: String, payload: JsonObject): SignalEvent =
        SignalEvent(ids.nextEventId(), callId, type, ids.nowMillis(), payload)

    private fun <T> runBlockingLite(block: suspend () -> T): T {
        val done = CountDownLatch(1)
        var value: T? = null
        var failure: Throwable? = null
        block.startCoroutine(
            object : Continuation<T> {
                override val context = EmptyCoroutineContext
                override fun resumeWith(result: Result<T>) {
                    value = result.getOrNull()
                    failure = result.exceptionOrNull()
                    done.countDown()
                }
            },
        )
        check(done.await(10, TimeUnit.SECONDS)) { "Timed out waiting for media session" }
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }
}
