package org.tinitalk.ui.call

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
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
@Config(sdk = [35])
class CallComponentsTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun hiddenActionLabelKeepsAccessibleDescription() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        composeRule.runOnUiThread {
            activity.get().setContent {
                TiniTalkTheme(darkTheme = true) {
                    RoundCallAction(
                        label = "Звук",
                        contentDescription = "Выбрать звук",
                        color = Color.DarkGray,
                        onClick = {},
                        showLabel = false,
                    )
                }
            }
        }

        composeRule.onNodeWithText("Звук").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Выбрать звук").assertExists()

        activity.pause().stop().destroy()
    }
}
