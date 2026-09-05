package org.tinitalk

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
    val target = withContext(Dispatchers.IO) {
        resolveContactCallTarget(AuthStore(store, AndroidKeystoreTokenCipher()), ContactCache(store), peer)
    }
    if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return null
    val current = CallServiceState.snapshot()
    if (current.phase != CallPhase.Idle && current.phase != CallPhase.Ended) {
        startActivity(CallActivity.ongoingIntent(this))
        return null
    }
    if (target == null) return CallLaunchError(
        "Контакт недоступен",
        "Контакт удалён или вы вышли из учётной записи. Проверьте список контактов в TiniTalk.",
    )
    if (!target.contact.canCall) return CallLaunchError(
        "Пока нельзя позвонить",
        "Позвонить можно после того, как ${target.contact.displayName} добавит вас в свой список контактов.",
    )
    if (!networkAvailability().canStartNetworkAction()) return offlineCallError()
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
        return CallLaunchError("Нужен доступ к микрофону", "Разрешите доступ к микрофону, чтобы звонить.", needsMicrophone = true)
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
        OutgoingCallStartResult.Unavailable -> CallLaunchError("Не удалось начать звонок", "Проверьте подключённую учётную запись и попробуйте ещё раз.")
    }
}

private fun offlineCallError() = CallLaunchError("Нет подключения", "Проверьте подключение к интернету и попробуйте позвонить ещё раз.")

@Composable
internal fun CallLaunchErrorDialog(error: CallLaunchError, onDismiss: () -> Unit, onRequestMicrophone: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(error.title) },
        text = { Text(error.message) },
        confirmButton = {
            TextButton(onClick = if (error.needsMicrophone) onRequestMicrophone else onDismiss) {
                Text(if (error.needsMicrophone) "Разрешить" else "Понятно")
            }
        },
    )
}
