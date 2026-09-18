package org.tinitalk

import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.tinitalk.data.AccountId
import org.tinitalk.data.ApiException
import org.tinitalk.data.AuthSessionEvents
import org.tinitalk.data.AuthStore
import org.tinitalk.data.Contact
import org.tinitalk.data.ContactPage
import org.tinitalk.data.ContactRepository
import org.tinitalk.data.HouseholdApi
import org.tinitalk.data.MemoryKeyValueStore
import org.tinitalk.data.PrefixTokenCipher
import org.tinitalk.data.Profile
import org.tinitalk.data.ServerInfo
import org.tinitalk.data.Session
import org.tinitalk.push.AccountWebPushRegistration
import org.tinitalk.push.StoredWebPushConfig
import org.tinitalk.push.WebPushClientConfig
import org.tinitalk.push.WebPushKeys
import org.tinitalk.push.WebPushSubscription
import org.tinitalk.ui.AccountPage
import org.tinitalk.ui.AccountSummary
import org.tinitalk.ui.MainScreenState

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainActivityLegacyAccountAdditionTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Before fun clearPreviousAuthEvent() = AuthSessionEvents.clear()

    @Test fun lostConfigResponseLeavesTheFormAndAllowsActivationToResume() = checkAddition(failClaim = false, unauthorized = false)

    @Test fun lostClaimResponseLeavesTheFormAndAllowsActivationToResume() = checkAddition(failClaim = true, unauthorized = false)

    @Test fun rejectedConfigTokenKeepsTheFormAndAllowsCorrectedToken() = checkAddition(failClaim = false, unauthorized = true)

    @Test fun rejectedClaimTokenKeepsTheFormAndAllowsCorrectedToken() = checkAddition(failClaim = true, unauthorized = true)

    private fun checkAddition(failClaim: Boolean, unauthorized: Boolean) {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val auth = AuthStore(MemoryKeyValueStore(), PrefixTokenCipher())
        val first = auth.upsert(Session("https://first.example", "bob", "first-token", sessionId = "first-session"))
        val failure = if (unauthorized) ApiException(401, "unauthorized") else IOException("response lost")
        var interrupted = true
        var configRequests = 0
        var claimRequests = 0
        val api = object : HouseholdApi {
            override fun serverInfo() = ServerInfo("tinitalk", "ok", 4, features = setOf("webpush_v1"))
            override fun webPushConfig(): WebPushClientConfig {
                configRequests++
                if (interrupted && !failClaim) throw failure
                return WebPushClientConfig(
                    "BNVQmPpYlVnSqeE5_UfDgJQG4YIqq7FPPHUZ6riR5TqQh_9ZgfkrdmHH99yqCGMiMSRuOJ5hK3sLrx_cUpnF4U4",
                    "legacy-config",
                )
            }

            override fun claimSession(deviceId: String, subscription: WebPushSubscription, configId: String): String {
                claimRequests++
                if (interrupted && failClaim) throw failure
                return "legacy-session"
            }

            override fun me() = Profile("alice", "alice")
            override fun contactsPage(limit: Int, cursor: String) = ContactPage(listOf(Contact("anna", "Мама")), "")
            override fun updateContactName(login: String, customName: String) = error("unexpected request")
            override fun calls(limit: Int, before: Long, peerLogin: String?) = error("unexpected request")
            override fun markCallsRead(throughId: Long, peerLogin: String?) = error("unexpected request")
        }
        val registration = object : AccountWebPushRegistration {
            override fun subscribe(accountId: AccountId, config: StoredWebPushConfig) = WebPushSubscription(
                "https://push.example/subscription", WebPushKeys("p256dh", "auth"),
            )
            override fun restore(accountId: AccountId, config: StoredWebPushConfig) = Unit
            override fun unsubscribe(accountId: AccountId) = Unit
        }
        val repository = ContactRepository(auth, registration, apiFactory = { _, _, _, _ -> api })
        MainActivity::class.java.getDeclaredField("repository").apply { isAccessible = true }.set(activity, repository)
        MainActivity::class.java.getDeclaredField("authStore").apply { isAccessible = true }.set(activity, auth)
        // Hold the automatic refresh while the connection is unavailable; resume it deterministically below.
        MainActivity::class.java.getDeclaredField("contactsSyncing").apply { isAccessible = true }.setBoolean(activity, true)
        try {
            compose.runOnIdle {
                val before = MainScreenState(
                    restoring = false, signedIn = true, accountPage = AccountPage.AddAccount, addingAccount = true,
                    accounts = listOf(AccountSummary(first.id, first.session.url, first.session.login, null)),
                )
                MainActivity::class.java.getDeclaredMethod("setScreenState", MainScreenState::class.java)
                    .apply { isAccessible = true }.invoke(activity, before)
            }
            assertThrows(failure.javaClass) {
                repository.addAccount("legacy.example", "alice", "legacy-token", "phone")
            }
            assertEquals(first, auth.get(first.id))
            val staged = auth.list().firstOrNull { it.id != first.id }
            assertEquals(unauthorized, staged == null)
            compose.runOnIdle {
                MainActivity::class.java.getDeclaredMethod("applyAccountAdditionOutcome", AccountAdditionOutcome::class.java)
                    .apply { isAccessible = true }.invoke(activity, AccountAdditionOutcome.Failed("Не удалось подключиться к серверу"))
                val state = MainActivity::class.java.getDeclaredMethod("getScreenState")
                    .apply { isAccessible = true }.invoke(activity) as MainScreenState
                assertEquals(if (unauthorized) AccountPage.AddAccount else AccountPage.Main, state.accountPage)
                assertTrue(state.signedIn)
                assertFalse(state.addingAccount)
                assertFalse(state.addAccountPasswordSetupRequired)
                assertEquals(auth.list().map { it.id }, state.accounts.map { it.id })
                if (unauthorized) assertNotNull(state.addAccountErrorMessage) else assertNull(state.addAccountErrorMessage)
            }
            interrupted = false
            val active = if (unauthorized) {
                repository.addAccount("legacy.example", "alice", "corrected-token", "phone").account
            } else {
                assertNotNull(repository.refreshContacts(requireNotNull(staged).id, "phone"))
                requireNotNull(auth.get(staged.id))
            }
            assertEquals("legacy-session", repository.restorableSession(active.id)?.sessionId)
            assertEquals(if (unauthorized) "corrected-token" else "legacy-token", active.session.token)
            assertEquals(2, auth.list().size)
            assertEquals(first, auth.get(first.id))
            assertEquals(2, configRequests)
            assertEquals(if (failClaim) 2 else 1, claimRequests)
            if (!unauthorized) assertEquals(requireNotNull(staged).id, active.id)
        } finally {
            controller.pause().stop().destroy()
            AuthSessionEvents.clear()
        }
    }
}
