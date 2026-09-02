package org.tinitalk.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.tinitalk.ContactOpenRequest
import org.tinitalk.data.AccountContact
import org.tinitalk.data.AccountHistory
import org.tinitalk.data.AccountId
import org.tinitalk.data.AccountPeerKey
import org.tinitalk.data.CallHistoryItem
import org.tinitalk.data.Contact
import org.tinitalk.data.ServerCheckDetails
import org.tinitalk.data.ServerCheckResult
import org.tinitalk.permissions.AppPermissionsState
import org.tinitalk.ui.theme.TiniTalkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp")
class MainScreenContactNavigationTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun requestWaitsForContactsAndSelectsExactAccountWhenLoginsMatch() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val firstAccount = AccountId("first-account")
        val secondAccount = AccountId("second-account")
        val first = AccountContact(firstAccount, "https://first.example", Contact("same", "First person"))
        val second = AccountContact(secondAccount, "https://second.example", Contact("same", "Second person"))
        val request = ContactOpenRequest(1, second.peerKey)
        var state by mutableStateOf(
            MainScreenState(
                restoring = false,
                signedIn = true,
                permissions = AppPermissionsState(true, true, true),
                accounts = listOf(
                    AccountSummary(firstAccount, first.serverUrl, "owner-one", "Owner one"),
                    AccountSummary(secondAccount, second.serverUrl, "owner-two", "Owner two"),
                ),
            ),
        )
        var handled: ContactOpenRequest? = null
        var historyPeer: AccountPeerKey? = null
        composeRule.runOnUiThread {
            activity.get().setContent {
                TiniTalkTheme(darkTheme = true) {
                    MainScreen(
                        state = state,
                        contactNameUpdate = ContactNameUpdateState(),
                        ongoingCall = null,
                        loginResetKey = 0,
                        contactOpenRequest = request,
                        onContactOpenRequestHandled = { handled = it },
                        onSignIn = { _, _, _ -> },
                        onCheckServer = { ServerCheckResult.Available },
                        onCheckServerDetails = {
                            ServerCheckDetails(ServerCheckResult.Available, apiVersion = 1)
                        },
                        onRequestNotifications = {},
                        onRequestMicrophone = {},
                        onRequestFullScreenCalls = {},
                        onRefreshPermissions = {},
                        onCall = {},
                        onRenameContact = { _, _ -> },
                        onRenameHandled = {},
                        onOpenCall = {},
                        onContactsVisible = {},
                        onRefreshContacts = {},
                        onContactsRefreshMessageHandled = {},
                        onHistoryVisible = {},
                        onLoadMoreHistory = {},
                        onContactHistoryVisible = { historyPeer = it },
                        onContactHistoryHidden = {},
                        onLoadMoreContactHistory = {},
                        onRetryContactHistory = {},
                        onOpenProfile = {},
                        onCloseProfile = {},
                        onOpenAddAccount = {},
                        onCloseAddAccount = {},
                        onAddAccount = { _, _, _ -> },
                        onRemoveAccount = {},
                    )
                }
            }
        }

        composeRule.waitForIdle()
        assertNull(handled)

        composeRule.runOnUiThread { state = state.copy(accountContacts = listOf(first, second)) }

        composeRule.onNodeWithText("Second person").assertIsDisplayed()
        assertEquals(request, handled)
        assertEquals(second.peerKey, historyPeer)

        activity.pause().stop().destroy()
    }

    @Test
    fun globalHistoryRowOpensExactAccountContactWhenLoginsMatch() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val firstAccount = AccountId("first-account")
        val secondAccount = AccountId("second-account")
        val first = AccountContact(firstAccount, "https://first.example", Contact("same", "First person"))
        val second = AccountContact(secondAccount, "https://second.example", Contact("same", "Second person"))
        val history = AccountHistory(
            accountId = secondAccount,
            serverUrl = second.serverUrl,
            item = CallHistoryItem(
                id = 42,
                peerLogin = second.login,
                peerName = second.displayName,
                direction = "incoming",
                outcome = "unanswered",
                reached = true,
                startedAt = 1_788_400_000,
                durationSeconds = 0,
            ),
        )
        var historyPeer: AccountPeerKey? = null
        composeRule.runOnUiThread {
            activity.get().setContent {
                TiniTalkTheme(darkTheme = true) {
                    MainScreen(
                        state = MainScreenState(
                            restoring = false,
                            signedIn = true,
                            accountContacts = listOf(first, second),
                            accountHistory = listOf(history),
                            historyLoaded = true,
                            permissions = AppPermissionsState(true, true, true),
                            accounts = listOf(
                                AccountSummary(firstAccount, first.serverUrl, "owner-one", "Owner one"),
                                AccountSummary(secondAccount, second.serverUrl, "owner-two", "Owner two"),
                            ),
                        ),
                        contactNameUpdate = ContactNameUpdateState(),
                        ongoingCall = null,
                        loginResetKey = 0,
                        onSignIn = { _, _, _ -> },
                        onCheckServer = { ServerCheckResult.Available },
                        onCheckServerDetails = {
                            ServerCheckDetails(ServerCheckResult.Available, apiVersion = 1)
                        },
                        onRequestNotifications = {},
                        onRequestMicrophone = {},
                        onRequestFullScreenCalls = {},
                        onRefreshPermissions = {},
                        onCall = {},
                        onRenameContact = { _, _ -> },
                        onRenameHandled = {},
                        onOpenCall = {},
                        onContactsVisible = {},
                        onRefreshContacts = {},
                        onContactsRefreshMessageHandled = {},
                        onHistoryVisible = {},
                        onLoadMoreHistory = {},
                        onContactHistoryVisible = { historyPeer = it },
                        onContactHistoryHidden = {},
                        onLoadMoreContactHistory = {},
                        onRetryContactHistory = {},
                        onOpenProfile = {},
                        onCloseProfile = {},
                        onOpenAddAccount = {},
                        onCloseAddAccount = {},
                        onAddAccount = { _, _, _ -> },
                        onRemoveAccount = {},
                    )
                }
            }
        }

        composeRule.onNodeWithContentDescription("История").performClick()
        val secondHistoryRow = hasContentDescription("Second person", substring = true) and hasClickAction()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(secondHistoryRow).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(secondHistoryRow).performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) { historyPeer == second.peerKey }
        composeRule.onNodeWithText("Second person").assertIsDisplayed()
        assertEquals(second.peerKey, historyPeer)

        activity.pause().stop().destroy()
    }
}
