package org.tinitalk

import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.tinitalk.data.AccountPeerKey
import org.tinitalk.data.AccountRecord
import org.tinitalk.data.AuthStore
import org.tinitalk.data.ContactRepository
import org.tinitalk.data.FavoriteContactsStore
import org.tinitalk.data.MemoryKeyValueStore
import org.tinitalk.data.PrefixTokenCipher
import org.tinitalk.data.Session
import org.tinitalk.ui.AccountPage
import org.tinitalk.ui.MainScreenState

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainActivityPasswordRecoveryTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun lastAccountReturnsToLoginWithItsServerAndLogin() = checkRecovery(additional = false)

    @Test fun additionalAccountReturnsToLoginWithoutLoggingOutOtherServers() = checkRecovery(additional = true)

    private fun checkRecovery(additional: Boolean) {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val auth = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher())
        val other = if (additional) auth.upsert(Session("https://other.example", "bob", "other-token", sessionId = "other-session")) else null
        val failed = auth.upsert(Session("https://family.example", "alice", "old-token", sessionId = "old-session"))
        auth.requireSignInIfCurrent(failed.id, failed.session)
        val favorites = FavoriteContactsStore(activity)
        favorites.setFavorite(AccountPeerKey(failed.id, "anna"), true)
        other?.let { favorites.setFavorite(AccountPeerKey(it.id, "anna"), true) }
        val repo = ContactRepository(auth, apiFactory = { _, _, _, _ -> error("no network in this navigation test") })
        MainActivity::class.java.getDeclaredField("repository").apply { isAccessible = true }.set(activity, repo)
        MainActivity::class.java.getDeclaredField("authStore").apply { isAccessible = true }.set(activity, auth)
        try {
            compose.runOnIdle {
                MainActivity::class.java.getDeclaredMethod("showPasswordSignInRecovery", AccountRecord::class.java)
                    .apply { isAccessible = true }.invoke(activity, failed)
                val state = MainActivity::class.java.getDeclaredMethod("getScreenState")
                    .apply { isAccessible = true }.invoke(activity) as MainScreenState
                assertFalse(state.restoring)
                assertFalse(state.passwordChanging)
                assertFalse(state.passwordSetupRequired)
                assertFalse(state.addAccountPasswordSetupRequired)
                assertEquals("alice", state.signInRecovery?.login)
                assertEquals("https://family.example", state.signInRecovery?.serverUrl)
                assertEquals(additional, state.signedIn)
                assertEquals(if (additional) AccountPage.AddAccount else AccountPage.Main, state.accountPage)
                val message = if (additional) state.addAccountErrorMessage else state.errorMessage
                assertTrue(message?.contains("Войдите с новым паролем") == true)
                assertEquals(listOfNotNull(other), repo.accounts())
                assertTrue(AccountPeerKey(failed.id, "anna") in favorites.load())
                if (other != null) assertTrue(AccountPeerKey(other.id, "anna") in favorites.load())
            }
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
