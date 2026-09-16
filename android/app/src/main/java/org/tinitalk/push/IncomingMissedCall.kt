package org.tinitalk.push

import org.tinitalk.missed.MissedCallTarget
import java.time.Instant

/** Translate push metadata once; missed-call state does not depend on push/notification types. */
internal fun IncomingInvite.toMissedCall(): MissedCallTarget? {
    val login = callerLogin?.takeIf(String::isNotBlank) ?: return null
    return MissedCallTarget(
        // Older servers omit the start; do not guess it from the ringing TTL.
        occurredAt = (startedAt ?: Instant.now()).epochSecond,
        accountId = accountId,
        login = login,
        name = caller.takeIf(String::isNotBlank),
        redialBinding = sessionBinding,
        missedCount = 1,
        callId = callId,
    )
}
