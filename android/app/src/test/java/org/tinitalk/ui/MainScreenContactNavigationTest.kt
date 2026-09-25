package org.tinitalk.ui

import org.tinitalk.R
import org.tinitalk.i18n.appString

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import org.tinitalk.ContactOpenRequest
import org.tinitalk.data.AccountContact
import org.tinitalk.data.AccountHistory
import org.tinitalk.data.AccountId
import org.tinitalk.data.AccountPeerKey
import org.tinitalk.data.CallHistoryItem
import org.tinitalk.data.Contact
import org.tinitalk.data.FavoriteContactsStore
import org.tinitalk.data.ServerCheckDetails
import org.tinitalk.data.ServerCheckResult
import org.tinitalk.permissions.AppPermissionsState
import org.tinitalk.ui.theme.TiniTalkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ru-w360dp-h800dp")
class MainScreenContactNavigationTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    @Config(qualifiers = "w1000dp-h400dp-land-mdpi")
    fun landscapeSwipesSwitchOnlyContactTabsIncludingTheirGutters() {
        checkSwipesSwitchOnlyContactTabs()
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-port-mdpi")
    fun portraitSwipesSwitchOnlyContactTabs() {
        checkSwipesSwitchOnlyContactTabs()
    }

    private fun checkSwipesSwitchOnlyContactTabs() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val account = AccountId("account")
        val contact = AccountContact(account, "https://example.com", Contact("alice", "Alice"))
        val other = AccountContact(account, contact.serverUrl, Contact("bob", "Bob"))
        val store = FavoriteContactsStore(activity.get())
        var visiblePage = -1
        composeRule.runOnUiThread {
            activity.get().setContent {
                TiniTalkTheme(darkTheme = true) {
                    MainScreen(
                        state = MainScreenState(
                            restoring = false, signedIn = true,
                            accountContacts = listOf(contact, other), historyLoaded = true,
                            permissions = AppPermissionsState(true, true, true),
                            accounts = listOf(AccountSummary(account, contact.serverUrl, "owner", "Owner")),
                        ),
                        contactNameUpdate = ContactNameUpdateState(), ongoingCall = null, loginResetKey = 0,
                        onSignIn = { _, _, _ -> },
                        onCheckServer = { ServerCheckResult.Available },
                        onCheckServerDetails = { ServerCheckDetails(ServerCheckResult.Available, apiVersion = 1) },
                        onRequestNotifications = {}, onRequestMicrophone = {}, onRequestFullScreenCalls = {},
                        onRefreshPermissions = {}, onCall = {}, onRenameContact = { _, _ -> },
                        onRenameHandled = {}, onOpenCall = {},
                        onContactsVisible = { visiblePage = 0 }, onHistoryVisible = { visiblePage = 1 },
                        onRefreshContacts = {}, onContactsRefreshMessageHandled = {}, onLoadMoreHistory = {},
                        onContactHistoryVisible = {}, onContactHistoryHidden = {},
                        onLoadMoreContactHistory = {}, onRetryContactHistory = {},
                        onOpenProfile = {}, onCloseProfile = {}, onOpenAddAccount = {}, onCloseAddAccount = {},
                        onAddAccount = { _, _, _ -> }, onRemoveAccount = {},
                    )
                }
            }
        }
        try {
            val pager = composeRule.onNodeWithTag("main-pager")
            val viewport = pager.fetchSemanticsNode().boundsInRoot
            val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
            assertEquals(root.right, viewport.right, 1f)
            fun assertPage(page: Int) {
                composeRule.waitForIdle()
                assertEquals(page, visiblePage)
                val bounds = composeRule.onNodeWithTag("main-page-$page").fetchSemanticsNode().boundsInRoot
                val content = composeRule.onNodeWithTag(if (page == 0) "contacts-page-content-0" else "main-page-content-$page")
                    .fetchSemanticsNode().boundsInRoot
                assertEquals(viewport, bounds)
                assertEquals(minOf(600f, viewport.width), content.width, 1f)
                assertEquals(viewport.center.x, content.center.x, 1f)
                if (viewport.width > 600f) {
                    assertTrue(content.left > viewport.left + 12f && content.right < viewport.right - 12f)
                }
            }
            fun swipe(tag: String, left: Boolean) {
                // In landscape, begin outside the centered list to exercise the gutters too.
                composeRule.onNodeWithTag(tag).performTouchInput {
                    val start = Offset(if (left) width - 12f else 12f, centerY)
                    val end = Offset(if (left) 12f else width - 12f, centerY)
                    swipe(start, end, durationMillis = 300)
                }
            }
            assertPage(0)
            // With no favorites there are no tabs, and neither swipe opens history.
            composeRule.onNodeWithText(appString(R.string.text_favorites_247)).assertDoesNotExist()
            swipe("contacts-pager", left = true)
            assertPage(0)
            swipe("contacts-pager", left = false)
            assertPage(0)
            composeRule.onNodeWithContentDescription(appString(R.string.text_history_251)).performClick()
            assertPage(1)
            swipe("main-pager", left = false)
            assertPage(1)
            swipe("main-pager", left = true)
            assertPage(1)
            composeRule.onNodeWithText(appString(R.string.text_contacts_298)).performClick()
            assertPage(0)

            composeRule.runOnIdle { store.setFavorite(contact.peerKey, true) }
            val favoritesTab = composeRule.onNodeWithText(appString(R.string.text_favorites_247))
            val allTab = composeRule.onNodeWithText(appString(R.string.text_all_248))
            favoritesTab.assertIsSelected()
            composeRule.onNodeWithText("Bob").assertIsNotDisplayed()
            swipe("contacts-pager", left = true)
            allTab.assertIsSelected()
            composeRule.onNodeWithText("Bob").assertIsDisplayed()
            composeRule.onNodeWithText(appString(R.string.text_add_303)).assertIsDisplayed()
            swipe("contacts-pager", left = true)
            allTab.assertIsSelected()
            assertEquals(0, visiblePage)
            swipe("contacts-pager", left = false)
            favoritesTab.assertIsSelected()
            composeRule.onNodeWithText("Bob").assertIsNotDisplayed()
            composeRule.onNodeWithText(appString(R.string.text_add_303)).assertIsNotDisplayed()
            swipe("contacts-pager", left = false)
            favoritesTab.assertIsSelected()
            assertEquals(0, visiblePage)

            allTab.performClick().assertIsSelected()
            composeRule.onNodeWithText("Bob").assertIsDisplayed()
            composeRule.onNodeWithContentDescription(appString(R.string.text_history_251)).performClick()
            assertPage(1)
            composeRule.onNodeWithText(appString(R.string.text_contacts_298)).performClick()
            allTab.assertIsSelected()
            // Removing the last favorite while on All collapses to one ordinary contact list.
            composeRule.runOnIdle { store.setFavorite(contact.peerKey, false) }
            favoritesTab.assertDoesNotExist()
            allTab.assertDoesNotExist()
            composeRule.onNodeWithText("Bob").assertIsDisplayed()
            assertPage(0)
            swipe("contacts-pager", left = true)
            assertPage(0)
        } finally {
            activity.pause().stop().destroy()
        }
    }

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

        composeRule.onNodeWithContentDescription(appString(R.string.text_add_to_favorites_226)).performClick()
        composeRule.onNodeWithContentDescription(appString(R.string.text_remove_from_favorites_225)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(appString(R.string.text_back_101)).performClick()
        composeRule.onNodeWithText(appString(R.string.text_favorites_247)).assertIsDisplayed()
        composeRule.onNode(hasContentDescription(appString(R.string.text_in_favorites_307), substring = true)).assertDoesNotExist()
        composeRule.onNodeWithText(appString(R.string.text_add_114), substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("First person").assertDoesNotExist()
        composeRule.onNodeWithText(appString(R.string.text_all_248)).performClick()
        composeRule.onNode(hasContentDescription(appString(R.string.text_in_favorites_307), substring = true)).assertIsDisplayed()
        composeRule.onNodeWithText(appString(R.string.text_add_114), substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("First person").assertIsDisplayed()
        composeRule.onNode(hasContentDescription(appString(R.string.text_open_contact_value_306, "Second person"), substring = true)).performClick()
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithContentDescription(appString(R.string.text_remove_from_favorites_225)).performClick()
        composeRule.mainClock.advanceTimeBy(250)
        composeRule.onNodeWithText(appString(R.string.text_removed_from_favorites_299)).assertIsDisplayed()
        composeRule.mainClock.advanceTimeBy(3_300)
        composeRule.onNodeWithText(appString(R.string.text_removed_from_favorites_299)).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(appString(R.string.text_add_to_favorites_226)).performClick()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithContentDescription(appString(R.string.text_remove_from_favorites_225)).performClick()
        composeRule.mainClock.advanceTimeBy(250)
        composeRule.onNodeWithText(appString(R.string.text_undo_196)).performClick()
        composeRule.mainClock.advanceTimeBy(250)
        composeRule.onNodeWithContentDescription(appString(R.string.text_remove_from_favorites_225)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(appString(R.string.text_remove_from_favorites_225)).performClick()
        composeRule.mainClock.advanceTimeBy(250)
        composeRule.onNodeWithText(appString(R.string.text_removed_from_favorites_299)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(appString(R.string.text_back_101)).performClick()
        composeRule.mainClock.autoAdvance = true
        composeRule.onNodeWithText(appString(R.string.text_removed_from_favorites_299)).assertDoesNotExist()
        composeRule.onNodeWithText(appString(R.string.text_favorites_247)).assertDoesNotExist()
        composeRule.onNode(hasContentDescription("В избранном", substring = true)).assertDoesNotExist()
        composeRule.onNodeWithText("First person").assertIsDisplayed()

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

        composeRule.onNodeWithContentDescription(appString(R.string.text_history_251)).performClick()
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
