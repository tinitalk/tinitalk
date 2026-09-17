package org.tinitalk.call

import com.google.gson.JsonObject
import org.tinitalk.data.signal.SignalEvent
import org.tinitalk.data.signal.SignalFailure
import org.tinitalk.media.CancellableTask
import org.tinitalk.media.IceCandidateData
import org.tinitalk.media.IceServerData
import org.tinitalk.media.MediaSession
import org.tinitalk.media.TaskScheduler
import java.time.Instant

internal const val CredentialRefreshLeadMillis = 60_000L

/**
 * SDP/ICE negotiation, TURN credential renewal and signaling retries for one call.
 *
 * The owner calls this component under [lock]. Media and timer callbacks take the
 * same monitor: negotiation and camera teardown must not acquire separate locks.
 * Detaching a session does not dispose it; the owner first stops its video sources.
 */
internal class CallNegotiation(
    private val signal: SignalClient,
    private val mediaFactory: (
        String, Boolean, List<IceServerData>, (IceCandidateData) -> Unit,
        (List<IceCandidateData>) -> Unit, () -> Unit,
    ) -> MediaSession,
    private val ids: EventIds,
    private val scheduler: TaskScheduler,
    private val lock: Any,
    private val onLocalSdp: (String, String) -> Unit,
    private val onRemoteSdp: (String, String) -> Unit,
) {
    private data class LocalIceEvent(val sequence: Long, val event: SignalEvent)

    var session: MediaSession? = null
        private set
    private var active = false
    private var muted = false
    var callId: String? = null
        private set
    private var iceServers: List<IceServerData> = emptyList()
    var videoAllowed = false
        private set
    var configuredCallId: String? = null
        private set
    private var acceptedCallId: String? = null
    private var offerStartedCallId: String? = null
    private var offerAwaitingAnswerCallId: String? = null
    private var restartInFlightCallId: String? = null
    private var restartAgainCallId: String? = null
    private var restartRequestedCallId: String? = null
    private var restartRequestID: String? = null
    private var pendingRestart: SignalEvent? = null
    private var pendingRestartRequest: SignalEvent? = null
    @Volatile private var localIceGeneration: String? = null
    private var remoteIceGeneration: String? = null
    private val localCandidateGenerations = mutableMapOf<IceCandidateData, String?>()
    private var credentialRefreshTask: CancellableTask? = null
    private var iceRetryTask: CancellableTask? = null
    private var iceRetryAtMillis: Long? = null
    private var restartRetryTask: CancellableTask? = null
    private var restartRequestRetryTask: CancellableTask? = null
    private var pendingOffer: SignalEvent? = null
    private val pendingIce = ArrayDeque<Pair<String, IceCandidateData>>()
    private var nextLocalIceSequence = 0L
    private val recentLocalIceEvents = linkedMapOf<String, LocalIceEvent>()
    private val rateLimitedIceEvents = linkedMapOf<String, LocalIceEvent>()

    fun setMuted(value: Boolean) {
        muted = value
        session?.setMuted(value)
    }

    fun setActive(value: Boolean) {
        active = value
        session?.setActive(value)
    }

    fun onAccepted(nextCallId: String, offerer: Boolean) {
        acceptedCallId = nextCallId.takeIf { offerer }
        if (offerer) startOfferWhenReady(nextCallId)
    }

    fun onOffer(event: SignalEvent) {
        if (configuredCallId == event.callId) answerOffer(event) else pendingOffer = event
    }

    fun onAnswer(event: SignalEvent) {
        session?.takeIf { callId == event.callId }?.let { media ->
            onRemoteSdp(event.callId, event.payload["sdp"].asString)
            awaitMediaOperation { media.setAnswer(event.payload["sdp"].asString) }
            completeLocalOffer(event.callId)
        }
    }

    fun onRemoteRestart(event: SignalEvent) {
        restartInFlightCallId = event.callId
        clearPendingRestartRequest(event.callId)
        remoteIceGeneration = event.id
        session?.beginRemoteDescription()
    }

    fun onIce(event: SignalEvent) {
        val generation = event.payload.restartID()
        if (generation != null && generation != remoteIceGeneration) return
        if (event.payload.isIceRemoval()) {
            removeIceCandidates(event.callId, event.payload.parseIceCandidates())
            return
        }
        val candidate = IceCandidateData(
            sdpMid = event.payload["sdp_mid"].asString,
            sdpMLineIndex = event.payload["sdp_mline_index"].asInt,
            candidate = event.payload["candidate"].asString,
        )
        val media = session
        if (media != null && callId == event.callId) {
            awaitMediaOperation { media.addIceCandidate(candidate) }
        } else {
            if (pendingIce.size == SignalEvent.EVENT_BUFFER_LIMIT) pendingIce.removeFirst()
            pendingIce.addLast(event.callId to candidate)
        }
    }

    fun onSignalConnected() {
        if (restartRetryTask == null) pendingRestart?.let { signal.send(it) }
        if (restartRequestRetryTask == null) pendingRestartRequest?.let { signal.send(it) }
    }

    fun onSignalFailure(failure: SignalFailure) {
        when (failure.code) {
            "ice_rate_limited" -> {
                val eventId = failure.eventId ?: return
                val rejected = recentLocalIceEvents[eventId] ?: return
                if (failure.callId != rejected.event.callId || callId != rejected.event.callId) return
                val delayMillis = failure.retryAfterMillis?.coerceAtLeast(1L) ?: return
                if (rateLimitedIceEvents.size == SignalEvent.EVENT_BUFFER_LIMIT && rejected.event.id !in rateLimitedIceEvents) {
                    rateLimitedIceEvents.remove(rateLimitedIceEvents.keys.first())
                }
                rateLimitedIceEvents[rejected.event.id] = rejected
                val retryAtMillis = ids.nowMillis() + delayMillis
                if (iceRetryAtMillis?.let { it >= retryAtMillis } == true) return
                iceRetryTask?.cancel()
                iceRetryAtMillis = retryAtMillis
                iceRetryTask = scheduler.schedule(delayMillis) {
                    synchronized(lock) {
                        iceRetryTask = null
                        iceRetryAtMillis = null
                        val pending = rateLimitedIceEvents.values
                            .filter { it.event.callId == callId }
                            .sortedBy(LocalIceEvent::sequence)
                            .map(LocalIceEvent::event)
                        rateLimitedIceEvents.clear()
                        pending.forEach { signal.send(it) }
                    }
                }
            }
            "ice_restart_rate_limited" -> {
                val restart = pendingRestart ?: return
                if (failure.callId != restart.callId || failure.eventId != restart.id) return
                val delayMillis = failure.retryAfterMillis?.coerceAtLeast(1L) ?: return
                restartRetryTask?.cancel()
                restartRetryTask = scheduler.schedule(delayMillis) {
                    synchronized(lock) {
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
                    synchronized(lock) {
                        restartRequestRetryTask = null
                        val pending = pendingRestartRequest
                        if (pending?.id == request.id && callId == request.callId) signal.send(pending)
                    }
                }
            }
        }
    }

    fun cancelPendingTasks() {
        credentialRefreshTask?.cancel()
        credentialRefreshTask = null
        iceRetryTask?.cancel()
        iceRetryTask = null
        iceRetryAtMillis = null
        restartRetryTask?.cancel()
        restartRetryTask = null
        restartRequestRetryTask?.cancel()
        restartRequestRetryTask = null
    }

    fun detachSession(): MediaSession? {
        val media = session
        session = null
        callId = null
        iceServers = emptyList()
        videoAllowed = false
        configuredCallId = null
        acceptedCallId = null
        offerStartedCallId = null
        offerAwaitingAnswerCallId = null
        restartInFlightCallId = null
        restartAgainCallId = null
        restartRequestedCallId = null
        restartRequestID = null
        pendingRestart = null
        pendingRestartRequest = null
        localIceGeneration = null
        remoteIceGeneration = null
        localCandidateGenerations.clear()
        pendingOffer = null
        pendingIce.clear()
        nextLocalIceSequence = 0L
        recentLocalIceEvents.clear()
        rateLimitedIceEvents.clear()
        active = false
        muted = false
        return media
    }

    fun configure(
        event: SignalEvent,
        nextVideoAllowed: Boolean,
        beforeSdp: () -> Unit,
    ) {
        iceServers = event.payload.parseIceServers()
        videoAllowed = nextVideoAllowed
        configuredCallId = event.callId
        beforeSdp()
        event.payload.restartID()?.let { localIceGeneration = it }
        session?.takeIf { callId == event.callId }?.let { media ->
            awaitMediaOperation { media.updateIceServers(iceServers) }
        }
        if (restartRequestedCallId == event.callId && restartRequestID == event.payload.restartID()) {
            restartRetryTask?.cancel()
            restartRetryTask = null
            val offer = awaitMediaOperation { ensureSession(event.callId).restartIce() }
            restartRequestedCallId = null
            restartRequestID = null
            pendingRestart = null
            offerAwaitingAnswerCallId = event.callId
            onLocalSdp(event.callId, offer)
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

    private fun startOfferWhenReady(nextCallId: String) {
        if (acceptedCallId != nextCallId || configuredCallId != nextCallId || offerStartedCallId == nextCallId) return
        offerStartedCallId = nextCallId
        val offer = awaitMediaOperation { ensureSession(nextCallId).createOffer() }
        offerAwaitingAnswerCallId = nextCallId
        onLocalSdp(nextCallId, offer)
        sendSdp(nextCallId, "rtc.offer", offer)
    }

    private fun answerOffer(event: SignalEvent) {
        val remoteSdp = event.payload["sdp"].asString
        onRemoteSdp(event.callId, remoteSdp)
        val answer = awaitMediaOperation { ensureSession(event.callId).acceptOffer(remoteSdp) }
        onLocalSdp(event.callId, answer)
        sendSdp(event.callId, "rtc.answer", answer)
        completeRemoteOffer(event.callId)
    }

    private fun completeLocalOffer(nextCallId: String) {
        if (offerAwaitingAnswerCallId != nextCallId) return
        offerAwaitingAnswerCallId = null
        if (restartInFlightCallId == nextCallId) restartInFlightCallId = null
        runDeferredRestart(nextCallId)
    }

    private fun completeRemoteOffer(nextCallId: String) {
        if (offerStartedCallId == nextCallId || restartInFlightCallId != nextCallId) return
        restartInFlightCallId = null
        runDeferredRestart(nextCallId)
    }

    private fun runDeferredRestart(nextCallId: String) {
        if (restartAgainCallId != nextCallId) return
        restartAgainCallId = null
        restartIce(nextCallId)
    }

    private fun ensureSession(nextCallId: String): MediaSession {
        val current = session
        if (current != null && callId == nextCallId) return current
        if (current != null) {
            awaitMediaOperation { current.close() }
            session = null
            callId = null
        }
        callId = nextCallId
        val created = mediaFactory(
            nextCallId,
            videoAllowed,
            iceServers,
            { candidate -> synchronized(lock) { sendIce(nextCallId, candidate) } },
            { candidates -> synchronized(lock) { sendIceCandidatesRemoved(nextCallId, candidates) } },
            { synchronized(lock) { restartIce(nextCallId) } },
        )
        session = created
        created.setActive(active)
        created.setMuted(muted)
        val queued = pendingIce.filter { it.first == nextCallId }.map { it.second }
        pendingIce.removeAll { it.first == nextCallId }
        if (queued.isNotEmpty()) awaitMediaOperation {
            queued.forEach { created.addIceCandidate(it) }
        }
        return created
    }

    fun restartIce(nextCallId: String) {
        if (callId != nextCallId) return
        if (offerAwaitingAnswerCallId == nextCallId || restartInFlightCallId == nextCallId) {
            restartAgainCallId = nextCallId
            return
        }
        restartInFlightCallId = nextCallId
        if (offerStartedCallId != nextCallId) {
            event(nextCallId, "rtc.restart.request", JsonObject()).also {
                pendingRestartRequest = it
                signal.send(it)
            }
            return
        }
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
        credentialRefreshTask = scheduler.schedule(delayMillis) { synchronized(lock) { restartIce(nextCallId) } }
    }

    private fun removeIceCandidates(nextCallId: String, candidates: List<IceCandidateData>) {
        if (candidates.isEmpty()) return
        val pendingForCall = pendingIce.filter { it.first == nextCallId && it.second in candidates }.toSet()
        pendingIce.removeAll(pendingForCall)
        val media = session
        if (media != null && callId == nextCallId) {
            val applied = candidates.filterNot { candidate -> pendingForCall.any { it.second == candidate } }
            if (applied.isNotEmpty()) awaitMediaOperation { media.removeIceCandidates(applied) }
        }
    }

    private fun sendSdp(callId: String, type: String, sdp: String) {
        val payload = JsonObject().apply { addProperty("sdp", sdp) }
        signal.send(event(callId, type, payload))
    }

    private fun sendIce(callId: String, candidate: IceCandidateData) {
        if (this.callId != callId) return
        val generation = localIceGeneration
        localCandidateGenerations[candidate] = generation
        val payload = JsonObject().apply {
            addProperty("sdp_mid", candidate.sdpMid)
            addProperty("sdp_mline_index", candidate.sdpMLineIndex)
            addProperty("candidate", candidate.candidate)
            generation?.let { addProperty("restart_id", it) }
        }
        sendLocalIceEvent(event(callId, "rtc.ice", payload))
    }

    private fun sendIceCandidatesRemoved(callId: String, candidates: List<IceCandidateData>) {
        if (this.callId != callId) return
        candidates.groupBy { candidate ->
            if (localCandidateGenerations.containsKey(candidate)) {
                localCandidateGenerations.remove(candidate)
            } else {
                localIceGeneration
            }
        }.forEach { (generation, generationCandidates) ->
            sendIceRemovalBatches(callId, generation, generationCandidates)
        }
    }

    private fun sendIceRemovalBatches(callId: String, generation: String?, candidates: List<IceCandidateData>) {
        val batch = mutableListOf<IceCandidateData>()
        candidates.forEach { candidate ->
            val expanded = batch + candidate
            if (iceRemovalFits(callId, generation, expanded)) {
                batch += candidate
            } else {
                sendIceRemovalBatch(callId, generation, batch)
                batch.clear()
                if (iceRemovalFits(callId, generation, listOf(candidate))) batch += candidate
            }
        }
        sendIceRemovalBatch(callId, generation, batch)
    }

    private fun sendIceRemovalBatch(callId: String, generation: String?, candidates: List<IceCandidateData>) {
        if (candidates.isEmpty()) return
        sendLocalIceEvent(event(callId, "rtc.ice", iceRemovalPayload(candidates, generation)))
    }

    private fun sendLocalIceEvent(event: SignalEvent) {
        if (recentLocalIceEvents.size == SignalEvent.EVENT_BUFFER_LIMIT) {
            recentLocalIceEvents.remove(recentLocalIceEvents.keys.first())
        }
        recentLocalIceEvents[event.id] = LocalIceEvent(nextLocalIceSequence++, event)
        signal.send(event)
    }

    private fun iceRemovalPayload(candidates: List<IceCandidateData>, generation: String?): JsonObject {
        val first = candidates.first()
        return JsonObject().apply {
            addProperty("removed", true)
            add("candidates", com.google.gson.JsonArray().apply {
                candidates.forEach { add(it.toJson()) }
            })
            addProperty("sdp_mid", first.sdpMid)
            addProperty("sdp_mline_index", first.sdpMLineIndex)
            addProperty("candidate", first.candidate)
            generation?.let { addProperty("restart_id", it) }
        }
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

    private fun JsonObject.isIceRemoval(): Boolean =
        get("removed")?.takeIf { it.isJsonPrimitive }?.asBoolean == true

    private fun JsonObject.parseIceCandidates(): List<IceCandidateData> =
        getAsJsonArray("candidates")?.mapNotNull { element ->
            val candidate = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val sdp = candidate.get("candidate")?.takeIf { it.isJsonPrimitive }?.asString ?: return@mapNotNull null
            IceCandidateData(
                sdpMid = candidate.get("sdp_mid")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
                sdpMLineIndex = candidate.get("sdp_mline_index")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0,
                candidate = sdp,
            )
        }.orEmpty()

    private fun iceRemovalFits(callId: String, generation: String?, candidates: List<IceCandidateData>): Boolean =
        runCatching {
            SignalEvent(
                ICE_EVENT_SIZE_PROBE_ID,
                callId,
                "rtc.ice",
                ids.nowMillis(),
                iceRemovalPayload(candidates, generation),
            ).encode()
        }.isSuccess

    private fun IceCandidateData.toJson() = JsonObject().apply {
        addProperty("sdp_mid", sdpMid)
        addProperty("sdp_mline_index", sdpMLineIndex)
        addProperty("candidate", candidate)
    }

    private fun event(callId: String, type: String, payload: JsonObject): SignalEvent =
        SignalEvent(ids.nextEventId(), callId, type, ids.nowMillis(), payload)

    private companion object {
        const val ICE_EVENT_SIZE_PROBE_ID = "00000000-0000-0000-0000-000000000000"
    }
}
