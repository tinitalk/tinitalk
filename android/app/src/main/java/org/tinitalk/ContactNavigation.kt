package org.tinitalk

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.tinitalk.data.AccountId
import org.tinitalk.data.AccountPeerKey

data class ContactOpenRequest(
    val id: Long,
    val peer: AccountPeerKey,
)

private const val ActionOpenContact = "org.tinitalk.action.OPEN_CONTACT"
private const val ExtraContactAccountId = "contact_account_id"
private const val ExtraContactLogin = "contact_login"

internal fun contactOpenIntent(
    context: Context,
    peer: AccountPeerKey,
    notificationKey: String,
): Intent = Intent(context, MainActivity::class.java)
    .setAction(ActionOpenContact)
    .setData(
        Uri.Builder()
            .scheme("tinitalk")
            .authority("missed")
            .appendPath("contact")
            .appendPath(notificationKey)
            .build(),
    )
    .putExtra(ExtraContactAccountId, peer.accountId.value)
    .putExtra(ExtraContactLogin, peer.login)
    .addFlags(
        Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_CLEAR_TOP or
            Intent.FLAG_ACTIVITY_SINGLE_TOP,
    )

internal fun contactPeerFromIntent(intent: Intent?): AccountPeerKey? {
    if (intent?.action != ActionOpenContact) return null
    val accountId = intent.getStringExtra(ExtraContactAccountId)?.takeIf(String::isNotBlank) ?: return null
    val login = intent.getStringExtra(ExtraContactLogin)?.takeIf(String::isNotBlank) ?: return null
    return AccountPeerKey(AccountId(accountId), login)
}
