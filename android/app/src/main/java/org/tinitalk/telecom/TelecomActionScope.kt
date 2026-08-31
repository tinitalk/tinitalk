package org.tinitalk.telecom

import org.tinitalk.call.AccountCallKey
import org.tinitalk.call.CallPhase
import org.tinitalk.call.CallSnapshot
import java.time.Instant

internal object TelecomActionScope {
    fun acceptsCallback(
        snapshot: CallSnapshot,
        pendingIncomingCallKey: AccountCallKey?,
        pendingExpiresAt: Instant?,
        localTelecomCallKey: AccountCallKey?,
        callbackCallKey: AccountCallKey,
        now: Instant,
    ): Boolean =
        (snapshot.phase != CallPhase.Idle && snapshot.phase != CallPhase.Ended && localTelecomCallKey == callbackCallKey) ||
            (snapshot.phase == CallPhase.Idle && pendingIncomingCallKey == callbackCallKey && pendingExpiresAt?.isAfter(now) == true)

    fun acceptsSelection(snapshot: CallSnapshot, key: AccountCallKey): Boolean =
        snapshot.phase != CallPhase.Idle &&
            snapshot.phase != CallPhase.Ended &&
            snapshot.callKey == key

    fun telecomCallForSelection(
        snapshot: CallSnapshot,
        key: AccountCallKey,
        localTelecomCallKey: AccountCallKey?,
    ): AccountCallKey? = localTelecomCallKey?.takeIf { acceptsSelection(snapshot, key) }
}
