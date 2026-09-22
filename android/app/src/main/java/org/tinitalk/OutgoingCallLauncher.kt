package org.tinitalk

import org.tinitalk.i18n.appString

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tinitalk.call.CallPhase
import org.tinitalk.call.CallServiceState
import org.tinitalk.call.CallSessionBinding
import org.tinitalk.data.AccountContact
import org.tinitalk.data.AccountPeerKey
import org.tinitalk.data.AccountRecord
import org.tinitalk.data.AndroidKeystoreTokenCipher
import org.tinitalk.data.AuthStore
import org.tinitalk.data.ContactCache
import org.tinitalk.data.SharedPreferencesKeyValueStore
import org.tinitalk.network.networkAvailability
import org.tinitalk.telecom.CallForegroundService
import org.tinitalk.telecom.IncomingCallController
import org.tinitalk.telecom.OutgoingCallStartResult

internal data class CallLaunchError(
    val title: String,
    val message: String,
    val needsMicrophone: Boolean = false,
)

internal data class ContactCallTarget(val account: AccountRecord, val contact: AccountContact)

internal fun resolveContactCallTarget(
    auth: AuthStore,
    cache: ContactCache,
    peer: AccountPeerKey,
): ContactCallTarget? {
    val account = auth.get(peer.accountId) ?: return null
    if (peer.login == account.session.login) return null
    val contact = cache.load(account).items.firstOrNull { it.peerKey == peer } ?: return null
    return ContactCallTarget(account, contact)
}

/** A user action in a resumed activity; no network refresh is needed before dialing. */
internal suspend fun ComponentActivity.launchContactCall(peer: AccountPeerKey): CallLaunchError? {
    val store = SharedPreferencesKeyValueStore(this)
    val result = withContext(Dispatchers.IO) {
        runCatching { resolveContactCallTarget(AuthStore(store, AndroidKeystoreTokenCipher()), ContactCache(store), peer) }
    }
    if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return null
    val target = result.getOrElse {
        return CallLaunchError(appString(R.string.text_could_not_open_the_contact_49), appString(R.string.text_open_tinitalk_and_check_your_connected_account_50))
    }
    val current = CallServiceState.snapshot()
    if (current.phase != CallPhase.Idle && current.phase != CallPhase.Ended) {
        startActivity(CallActivity.ongoingIntent(this))
        return null
    }
    if (target == null) return CallLaunchError(
        appString(R.string.text_contact_unavailable_51),
        appString(R.string.text_the_contact_was_deleted_or_you_signed_out_check_your_contacts_in__52),
    )
    if (!target.contact.canCall) return CallLaunchError(
        appString(R.string.text_cannot_call_yet_53),
        appString(R.string.text_you_can_call_once_value_adds_you_to_their_contacts_54, target.contact.displayName),
    )
    if (!networkAvailability().canStartNetworkAction()) return offlineCallError()
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
        return CallLaunchError(appString(R.string.text_microphone_access_needed_55), appString(R.string.text_allow_microphone_access_to_make_calls_56), needsMicrophone = true)
    }
    return when (val start = CallForegroundService.tryStartOutgoing(
        this, peer, target.contact.displayName, CallSessionBinding.from(target.account.session),
    )) {
        is OutgoingCallStartResult.Started -> {
            startActivity(CallActivity.outgoingIntent(this, peer, target.contact.address, target.contact.displayName, start.key))
            null
        }
        is OutgoingCallStartResult.Busy -> {
            val incoming = IncomingCallController()
            val invite = incoming.load(this)?.invite?.takeIf { it.owner == start.owner }
            if (invite != null) incoming.openScreen(this, invite)
            else startActivity(CallActivity.ongoingIntent(this))
            null
        }
        OutgoingCallStartResult.Offline -> offlineCallError()
        OutgoingCallStartResult.Unavailable -> CallLaunchError(appString(R.string.text_could_not_start_the_call_7), appString(R.string.text_check_your_connected_account_and_try_again_57))
    }
}

private fun offlineCallError() = CallLaunchError(appString(R.string.text_no_connection_58), appString(R.string.text_check_your_internet_connection_and_try_calling_again_59))

@Composable
internal fun CallLaunchErrorDialog(error: CallLaunchError, onDismiss: () -> Unit, onRequestMicrophone: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(error.title) },
        text = { Text(error.message) },
        confirmButton = {
            TextButton(onClick = if (error.needsMicrophone) onRequestMicrophone else onDismiss) {
                Text(if (error.needsMicrophone) appString(R.string.text_allow_60) else appString(R.string.text_ok_61))
            }
        },
    )
}
