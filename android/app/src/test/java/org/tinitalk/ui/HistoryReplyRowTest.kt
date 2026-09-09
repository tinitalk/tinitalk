package org.tinitalk.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.tinitalk.data.CallHistoryItem
import org.tinitalk.ui.theme.TiniTalkTheme

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class HistoryReplyRowTest {
    @get:Rule val composeRule = createEmptyComposeRule()

    @Test
    fun bothHistoryLayoutsUseReplyTextAndKeepRowNavigationAtLargeFont() {
        val row = mutableStateOf(CallHistoryItem(1, "alice", "Alice", "outgoing", "rejected", true, 1787740200, 0))
        val showPeer = mutableStateOf(true)
        var clicks = 0
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        activity.get().setContent {
            TiniTalkTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.8f)) {
                    HistoryRow(row.value, showPeer = showPeer.value, onClick = { clicks++ })
                }
            }
        }
        val codes = listOf("cannot_talk", "call_me_later", "will_call_back")
        val received = listOf("Не могли говорить", "Просили перезвонить позже", "Обещали перезвонить")
        val sent = listOf("Вы не могли говорить", "Вы просили перезвонить позже", "Вы обещали перезвонить")
        for (general in listOf(true, false)) {
            for (incoming in listOf(false, true)) {
                codes.forEachIndexed { index, code ->
                    composeRule.runOnIdle {
                        showPeer.value = general
                        row.value = row.value.copy(direction = if (incoming) "incoming" else "outgoing", replyCode = code)
                    }
                    val expected = if (incoming) sent[index] else received[index]
                    composeRule.onNodeWithText(expected, useUnmergedTree = true).assertExists()
                    composeRule.onNodeWithText(if (incoming) "Вы отклонили вызов" else "Вызов отклонён").assertDoesNotExist()
                    val prefix = if (general) "Alice" else if (incoming) "Входящий" else "Исходящий"
                    composeRule.onNodeWithContentDescription("$prefix, $expected, ${historyTime(row.value.startedAt)}").performClick()
                }
            }
        }
        assertEquals(12, clicks)
        activity.pause().stop().destroy()
    }
}
