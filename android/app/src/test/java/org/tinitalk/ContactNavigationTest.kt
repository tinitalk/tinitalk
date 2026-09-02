package org.tinitalk

import android.content.Intent
import org.tinitalk.data.AccountId
import org.tinitalk.data.AccountPeerKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ContactNavigationTest {
    @Test
    fun contactIntentRoundTripsExactAccountScopedContact() {
        val context = RuntimeEnvironment.getApplication()
        val peer = AccountPeerKey(AccountId("account-b"), "same-login")

        val intent = contactOpenIntent(context, peer, "notification-key")

        assertEquals(peer, contactPeerFromIntent(intent))
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals("tinitalk://missed/contact/notification-key", intent.data.toString())
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
    }

    @Test
    fun parserRejectsOrdinaryAndIncompleteAppIntents() {
        val context = RuntimeEnvironment.getApplication()
        val valid = contactOpenIntent(
            context,
            AccountPeerKey(AccountId("account"), "anna"),
            "notification-key",
        )

        assertNull(contactPeerFromIntent(Intent(context, MainActivity::class.java)))
        assertNull(contactPeerFromIntent(Intent(valid).setAction(Intent.ACTION_VIEW)))
        assertNull(contactPeerFromIntent(Intent(valid).putExtra("contact_login", "")))
        assertNull(contactPeerFromIntent(null))
    }
}
