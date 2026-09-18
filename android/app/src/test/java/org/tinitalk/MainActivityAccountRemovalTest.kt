package org.tinitalk

import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.tinitalk.data.AccountPeerKey
import org.tinitalk.data.AuthStore
import org.tinitalk.data.ContactRepository
import org.tinitalk.data.FavoriteContactsStore
import org.tinitalk.data.MemoryKeyValueStore
import org.tinitalk.data.PrefixTokenCipher
import org.tinitalk.data.Session
import org.tinitalk.data.HouseholdApi
import org.tinitalk.data.ServerInfo

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainActivityAccountRemovalTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun removingLastAccountClearsItsFavorites() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val auth = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher())
        val account = auth.upsert(Session("https://example.com", "alice", "token"))
        val favorites = FavoriteContactsStore(activity)
        favorites.setFavorite(AccountPeerKey(account.id, "bob"), true)
        val api = object : HouseholdApi {
            override fun serverInfo() = ServerInfo("tinitalk", "ok", 4, features = setOf("webpush_v1"))
            override fun me() = error("unexpected request")
            override fun contactsPage(limit: Int, cursor: String) = error("unexpected request")
            override fun updateContactName(login: String, customName: String) = error("unexpected request")
            override fun calls(limit: Int, before: Long, peerLogin: String?) = error("unexpected request")
            override fun markCallsRead(throughId: Long, peerLogin: String?) = error("unexpected request")
        }
        MainActivity::class.java.getDeclaredField("repository").apply { isAccessible = true }
            .set(activity, ContactRepository(auth, apiFactory = { _, _, _, _ -> api }))
        val removing = MainActivity::class.java.getDeclaredField("accountRemovalInProgress").apply {
            isAccessible = true
        }
        try {
            compose.runOnIdle {
                MainActivity::class.java.declaredMethods.single { it.name.startsWith("removeAccount-") }
                    .apply { isAccessible = true }.invoke(activity, account.id.value)
            }
            compose.waitUntil(5_000) {
                ShadowLooper.idleMainLooper()
                !removing.getBoolean(activity)
            }
            compose.runOnIdle {
                assertTrue(auth.list().isEmpty())
                assertTrue("The last account must not leave favorite contacts behind", favorites.load().isEmpty())
            }
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
