package org.tinitalk.push

import com.google.gson.JsonObject
import org.tinitalk.call.CallSessionBinding
import org.tinitalk.call.SequencedSignalEvent
import org.tinitalk.data.AccountRecord
import org.tinitalk.data.signal.SignalEvent
import java.time.Instant
import java.util.UUID

/** Transport decoding only. Admission and presentation are shared with incoming push. */
internal object IncomingSocketPayload {
    fun fromEvent(
        account: AccountRecord,
        incoming: SequencedSignalEvent,
        name: (String) -> String?,
        now: Instant = Instant.now(),
    ): IncomingInvite? = runCatching {
        val event = incoming.event
        if (event.type != "call.incoming") return null
        val started = Instant.ofEpochMilli(event.sentAt)
        invite(account, event.callId, event.payload["caller_login"].asString, started,
            started.plusSeconds(SignalEvent.RING_TIMEOUT_SECONDS.toLong()), incoming.seq, name, now)
    }.getOrNull()

    fun fromSnapshot(
        account: AccountRecord,
        snapshot: JsonObject,
        name: (String) -> String?,
        now: Instant = Instant.now(),
    ): IncomingInvite? = runCatching {
        invite(account, snapshot["call_id"].asString, snapshot["caller_login"].asString,
            Instant.parse(snapshot["started_at"].asString), Instant.parse(snapshot["expires_at"].asString),
            snapshot["last_seq"].asLong, name, now)
    }.getOrNull()

    private fun invite(
        account: AccountRecord, callId: String, login: String, started: Instant, expires: Instant,
        seq: Long, name: (String) -> String?, now: Instant,
    ): IncomingInvite? {
        if (UUID.fromString(callId).toString() != callId.lowercase() || login.isBlank() ||
            !expires.isAfter(now) || !expires.isAfter(started) || seq < 1
        ) return null
        return IncomingInvite(account.id, CallSessionBinding.from(account.session), callId,
            name(login)?.takeIf(String::isNotBlank) ?: login, expires, login, seq, started)
    }
}
