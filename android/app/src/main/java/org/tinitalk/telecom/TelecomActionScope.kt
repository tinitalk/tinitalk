package org.tinitalk.telecom

import org.tinitalk.call.CallPhase
import org.tinitalk.call.CallSnapshot
import java.time.Instant

internal object TelecomActionScope {
    fun acceptsCallback(
        snapshot: CallSnapshot,
        pendingIncomingCallId: String?,
        pendingExpiresAt: Instant?,
        callId: String,
        now: Instant,
    ): Boolean =
        (snapshot.phase != CallPhase.Idle && snapshot.phase != CallPhase.Ended && snapshot.callId == callId) ||
            (snapshot.phase == CallPhase.Idle && pendingIncomingCallId == callId && pendingExpiresAt?.isAfter(now) == true)

    fun acceptsSelection(snapshot: CallSnapshot, callId: String): Boolean =
        snapshot.phase == CallPhase.Active && snapshot.callId == callId
}
