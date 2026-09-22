package org.tinitalk.i18n

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.tinitalk.R
import org.tinitalk.call.*
import org.tinitalk.data.AccountId
import org.tinitalk.data.ServerCheckDetails
import org.tinitalk.data.ServerCheckResult
import org.tinitalk.ui.AboutScreen
import org.tinitalk.ui.theme.TiniTalkTheme

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [26, 35], qualifiers = "ru-w360dp-h800dp", application = LocalizedTestApplication::class)
class AboutLanguageTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    @Config(qualifiers = "ru-w320dp-h480dp")
    fun pickerUpdatesTheOpenScreenWithoutChangingCallState() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        var back = false
        CallUiStateStore.begin(AccountCallKey(AccountId("language-test"), "call"), CallPeer("Alex"), CallDirection.Outgoing, CallPhase.Active)
        val call = CallUiStateStore.snapshot()
        try {
            compose.runOnUiThread {
                activity.get().setContent {
                    TiniTalkTheme {
                        AboutScreen("", onCheckServer = { ServerCheckDetails(ServerCheckResult.Available) }, onBack = { back = true })
                    }
                }
            }
            for ((tag, label) in AppLanguage.supported) {
                compose.onNodeWithText(appString(R.string.language_title)).performClick()
                compose.onNodeWithText(label).performScrollTo().performClick()
                compose.waitForIdle()
                assertEquals(tag, AppLanguage.selection)
                compose.onNodeWithText(appString(R.string.text_about_102)).assertIsDisplayed()
                compose.onNodeWithText(label).assertIsDisplayed()
                assertEquals(call, CallUiStateStore.snapshot())
                assertFalse(activity.get().isFinishing)
            }
            compose.onNodeWithContentDescription(appString(R.string.text_back_101)).performClick()
            assertTrue(back)
        } finally {
            CallUiStateStore.reset()
            activity.pause().stop().destroy()
        }
    }
}
