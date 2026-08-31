package org.tinitalk.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.tinitalk.data.ServerCheckDetails
import org.tinitalk.data.ServerCheckResult
import org.tinitalk.data.AccountId
import org.tinitalk.ui.theme.TiniTalkTheme
import org.junit.Rule
import org.junit.Assert.assertEquals
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
    fun menuItemsHaveLargeTapTargets() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        composeRule.runOnUiThread {
            activity.get().setContent {
                TiniTalkTheme(darkTheme = true) {
                    MainScreen(
                        state = MainScreenState(restoring = false, signedIn = true),
                        contactNameUpdate = ContactNameUpdateState(),
                        ongoingCall = null,
                        loginResetKey = 0,
                        defaultServerUrl = "https://talk.example.com",
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
                        onLoadMoreContacts = {},
                        onContactsRefreshMessageHandled = {},
                        onHistoryVisible = {},
                        onLoadMoreHistory = {},
                        onRetryHistory = {},
                        onContactHistoryVisible = {},
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

        composeRule.onNodeWithContentDescription("\u041c\u0435\u043d\u044e").performClick()
        composeRule.onNodeWithText("\u041e \u043f\u0440\u043e\u0433\u0440\u0430\u043c\u043c\u0435").assertHeightIsAtLeast(64.dp)
        composeRule.onNodeWithText("\u041f\u0440\u043e\u0444\u0438\u043b\u044c").assertHeightIsAtLeast(64.dp)

        activity.pause().stop().destroy()
    }

    @Test
    fun serverPresentationUsesDistinctConfiguredServersSafeHostsAndUnambiguousAboutServer() {
        assertEquals(
            false,
            shouldShowServerSubtitles(listOf(" https://same.example/ ", "https://same.example")),
        )
        assertEquals(
            true,
            shouldShowServerSubtitles(listOf("https://a.example", "https://b.example")),
        )
        assertEquals("talk.example", serverHostname("https://talk.example/path"))
        assertEquals("not a uri", serverHostname("not a uri"))
        assertEquals("https://same.example", configuredAboutServerUrl(listOf("https://same.example/", " https://same.example ")))
        assertEquals("", configuredAboutServerUrl(listOf("https://a.example", "https://b.example")))
    }
}
