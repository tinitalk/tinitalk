package org.tinitalk.push

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import java.time.Instant

/** One bounded record, independent of service handoff and scoped to the exact invite owner. */
internal class IncomingCallSilenceStore(private val preferences: SharedPreferences) {
    fun silence(invite: IncomingInvite) {
        preferences.edit {
            putString("owner", invite.owner.localId())
            putLong("expires_at", invite.expiresAt.toEpochMilli())
        }
    }

    fun isSilenced(invite: IncomingInvite, now: Instant = Instant.now()): Boolean =
        invite.owner.localId() == preferences.getString("owner", null) &&
            now.toEpochMilli() < preferences.getLong("expires_at", 0) && invite.expiresAt.isAfter(now)
}

internal fun incomingCallSilenceStore(context: Context) = IncomingCallSilenceStore(
    context.getSharedPreferences("incoming_call_silence", Context.MODE_PRIVATE),
)
