package org.tinitalk.shortcuts

import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.tinitalk.data.AccountId
import org.tinitalk.data.AccountPeerKey

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ContactShortcutsTest {
    @Test
    fun shortcutKeepsAccountIdentityAndCannotBeChangedUsingExtras() {
        val context = RuntimeEnvironment.getApplication()
        val first = AccountPeerKey(AccountId("first"), "anna")
        val second = AccountPeerKey(AccountId("second"), "anna")
        val intent = contactShortcutIntent(context, first)

        assertEquals(first, shortcutPeer(intent.putExtra("contact_account_id", "second")))
        assertEquals(second, shortcutPeer(contactShortcutIntent(context, second)))
        assertNotEquals(intent.data, contactShortcutIntent(context, second).data)
        assertNull(shortcutPeer(Intent(intent).setAction(Intent.ACTION_VIEW)))
        assertNull(shortcutPeer(Intent(intent).setData(null)))
        assertNull(shortcutPeer(null))
        val activity = context.packageManager.getActivityInfo(intent.component!!, 0)
        assertFalse(activity.exported)
    }
}
