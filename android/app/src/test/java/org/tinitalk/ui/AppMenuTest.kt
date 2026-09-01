package org.tinitalk.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.tinitalk.data.ServerCheckDetails
import org.tinitalk.data.ServerCheckResult
import org.tinitalk.data.AccountId
import org.tinitalk.data.AccountContact
import org.tinitalk.data.Contact
import org.tinitalk.ui.theme.TiniTalkTheme
import org.junit.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp")
class AppMenuTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun headerOpensAboutAndProfileDirectly() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        var profileOpened = false
        composeRule.runOnUiThread {
            activity.get().setContent {
                TiniTalkTheme(darkTheme = true) {
                    MainScreen(
                        state = MainScreenState(
                            restoring = false,
                            signedIn = true,
                            accounts = listOf(
                                AccountSummary(AccountId("account"), "https://talk.example", "alex", "Alex"),
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
                        onRetryHistory = {},
                        onContactHistoryVisible = {},
                        onContactHistoryHidden = {},
                        onLoadMoreContactHistory = {},
                        onRetryContactHistory = {},
                        onOpenProfile = { profileOpened = true },
                        onCloseProfile = {},
                        onOpenAddAccount = {},
                        onCloseAddAccount = {},
                        onAddAccount = { _, _, _ -> },
                        onRemoveAccount = {},
                    )
                }
            }
        }

        composeRule.onAllNodesWithContentDescription("Меню").assertCountEquals(0)
        composeRule.onNodeWithContentDescription("О программе").performClick()
        composeRule.onNodeWithText("О программе").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Назад").performClick()
        composeRule.onNodeWithContentDescription("Профиль").performClick()
        assertTrue(profileOpened)

        activity.pause().stop().destroy()
    }

    @Test
    fun profileShowsServerDetailsForEveryAccount() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        composeRule.runOnUiThread {
            activity.get().setContent {
                TiniTalkTheme(darkTheme = true) {
                    ProfileScreen(
                        accounts = listOf(
                            AccountSummary(AccountId("first"), "https://one.example", "alex", "Алексей"),
                            AccountSummary(AccountId("second"), "https://two.example", "maria", "Мария"),
                        ),
                        internetAvailable = true,
                        onCheckServer = { url ->
                            if (url == "https://one.example") {
                                ServerCheckDetails(ServerCheckResult.Available, apiVersion = 3, commit = "11111111")
                            } else {
                                ServerCheckDetails(ServerCheckResult.Available, apiVersion = 7, commit = "77777777")
                            }
                        },
                        onBack = {},
                        onAdd = {},
                        onRemoveAccount = {},
                    )
                }
            }
        }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Сервер TiniTalk доступен").fetchSemanticsNodes().size == 2
        }
        composeRule.onNodeWithText("API v3 (11111111)").assertIsDisplayed()
        composeRule.onNodeWithText("API v7 (77777777)").assertIsDisplayed()
        composeRule.onAllNodesWithText("Сервер TiniTalk доступен").assertCountEquals(2)
        composeRule.onAllNodesWithContentDescription("Сервер TiniTalk доступен").assertCountEquals(0)
        composeRule.onAllNodesWithContentDescription("Выйти").assertCountEquals(2)
        composeRule.onAllNodesWithText("Выйти").assertCountEquals(0)

        activity.pause().stop().destroy()
    }

    @Test
    fun serverPresentationUsesDistinctConfiguredServersSafeHostsAndUnambiguousAboutServer() {
        val duplicateA = AccountContact(AccountId("a"), "https://a.example", Contact("alex", " Саша "))
        val duplicateB = AccountContact(AccountId("b"), "https://b.example", Contact("sasha", "саша"))
        val unique = AccountContact(AccountId("a"), "https://a.example", Contact("maria", "Мария"))
        val sameServerA = AccountContact(AccountId("a"), "https://a.example", Contact("petr", "Пётр"))
        val sameServerB = AccountContact(AccountId("a"), "https://a.example/", Contact("petya", "ПЁТР"))

        assertEquals(
            setOf(duplicateA.peerKey, duplicateB.peerKey),
            contactsRequiringServerSubtitle(
                listOf(duplicateA, duplicateB, unique, sameServerA, sameServerB),
            ),
        )
        assertEquals("talk.example", serverHostname("https://talk.example/path"))
        assertEquals("talk.example/path", serverAddress("https://talk.example/path"))
        assertEquals("not a uri", serverHostname("not a uri"))
        assertEquals("https://same.example", configuredAboutServerUrl(listOf("https://same.example/", " https://same.example ")))
        assertEquals("", configuredAboutServerUrl(listOf("https://a.example", "https://b.example")))
    }
}
