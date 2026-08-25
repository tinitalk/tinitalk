package org.tinitalk.call

import com.google.gson.JsonObject
import org.tinitalk.data.signal.SignalEvent
import org.tinitalk.media.IceCandidateData
import org.tinitalk.media.IceServerData
import org.tinitalk.media.MediaSession
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ForegroundCallController(
    private val signal: SignalClient,
    private val mediaFactory: (String, List<IceServerData>, (IceCandidateData) -> Unit) -> MediaSession,
    private val ids: EventIds = UuidEventIds(),
) {
    private var session: MediaSession? = null
    private var callId: String? = null
    private var iceServers: List<IceServerData> = emptyList()

    fun onSignalEvent(snapshot: CallSnapshot, event: SignalEvent) {
        when (event.type) {
            "call.accept" -> if (snapshot.phase == CallPhase.Active) {
                val media = ensureSession(event.callId)
                val offer = runBlockingLite { media.createOffer() }
                sendSdp(event.callId, "rtc.offer", offer)
            }
            "rtc.offer" -> {
                val media = ensureSession(event.callId)
                val answer = runBlockingLite { media.acceptOffer(event.payload["sdp"].asString) }
                sendSdp(event.callId, "rtc.answer", answer)
            }
            "rtc.config" -> iceServers = event.payload.parseIceServers()
            "rtc.answer" -> session?.let { media ->
                runBlockingLite { media.setAnswer(event.payload["sdp"].asString) }
            }
            "rtc.ice" -> session?.let { media ->
                val candidate = IceCandidateData(
                    sdpMid = event.payload["sdp_mid"].asString,
                    sdpMLineIndex = event.payload["sdp_mline_index"].asInt,
                    candidate = event.payload["candidate"].asString,
                )
                runBlockingLite { media.addIceCandidate(candidate) }
            }
            "call.reject", "call.cancel", "call.end", "call.expire" -> close()
        }
    }

    fun setMuted(muted: Boolean) {
        session?.setMuted(muted)
    }

    fun close() {
        val media = session ?: return
        session = null
        callId = null
        runBlockingLite { media.close() }
    }

    private fun ensureSession(nextCallId: String): MediaSession {
        val current = session
        if (current != null && callId == nextCallId) return current
        close()
        callId = nextCallId
        return mediaFactory(nextCallId, iceServers) { candidate -> sendIce(nextCallId, candidate) }.also {
            session = it
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
            )
        }
    }

    private fun sendSdp(callId: String, type: String, sdp: String) {
        val payload = JsonObject().apply { addProperty("sdp", sdp) }
        signal.send(event(callId, type, payload))
    }

    private fun sendIce(callId: String, candidate: IceCandidateData) {
        val payload = JsonObject().apply {
            addProperty("sdp_mid", candidate.sdpMid)
            addProperty("sdp_mline_index", candidate.sdpMLineIndex)
            addProperty("candidate", candidate.candidate)
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
