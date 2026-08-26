package org.tinitalk.telecom

import org.tinitalk.call.CallPhase
import org.tinitalk.call.CallSnapshot
import java.time.Instant

internal object TelecomActionScope {
    fun acceptsCallback(
        snapshot: CallSnapshot,
        pendingIncomingCallId: String?,
        pendingExpiresAt: Instant?,
        localTelecomCallId: String?,
        callbackCallId: String,
        now: Instant,
    ): Boolean =
        (snapshot.phase != CallPhase.Idle && snapshot.phase != CallPhase.Ended && localTelecomCallId == callbackCallId) ||
            (snapshot.phase == CallPhase.Idle && pendingIncomingCallId == callbackCallId && pendingExpiresAt?.isAfter(now) == true)

    fun acceptsSelection(snapshot: CallSnapshot, callId: String): Boolean =
        snapshot.phase != CallPhase.Idle &&
            snapshot.phase != CallPhase.Ended &&
            snapshot.callId == callId

    fun telecomCallForSelection(snapshot: CallSnapshot, callId: String, localTelecomCallId: String?): String? =
        localTelecomCallId?.takeIf { acceptsSelection(snapshot, callId) }
}
