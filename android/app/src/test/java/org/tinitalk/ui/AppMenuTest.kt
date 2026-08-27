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
import org.tinitalk.ui.theme.TiniTalkTheme
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
                        onSignOut = {},
                    )
                }
            }
        }

        composeRule.onNodeWithContentDescription("\u041c\u0435\u043d\u044e").performClick()
        composeRule.onNodeWithText("\u041e \u043f\u0440\u043e\u0433\u0440\u0430\u043c\u043c\u0435").assertHeightIsAtLeast(64.dp)
        composeRule.onNodeWithText("\u0412\u044b\u0439\u0442\u0438 \u0438\u0437 \u0430\u043a\u043a\u0430\u0443\u043d\u0442\u0430").assertHeightIsAtLeast(64.dp)

        activity.pause().stop().destroy()
    }
}
