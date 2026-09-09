package org.tinitalk.ui.call

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.tinitalk.call.CallReplyCode
import org.tinitalk.ui.theme.TiniTalkTheme

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class IncomingReplySheetTest {
    @get:Rule val composeRule = createEmptyComposeRule()

    @Test
    @Config(qualifiers = "w411dp-h891dp-420dpi")
    fun visuallyClosedSheetImmediatelyRestoresCallButtonsAfterRepeatedSwipes() {
        var answered = 0
        var rejected = 0
        var replies = 0
        var touchSlop = 0f
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        activity.get().setContent {
            TiniTalkTheme {
                touchSlop = LocalViewConfiguration.current.touchSlop
                IncomingCallScreen("call", "Alice", replySupported = true,
                    onReply = { replies++ }, onAnswer = { answered++ }, onReject = { rejected++ })
            }
        }
        try {
            val collapsedTop = composeRule.onNodeWithTag("incoming_reply_handle").fetchSemanticsNode().boundsInRoot.top
            repeat(6) { cycle ->
                composeRule.onNodeWithTag("incoming_reply_handle").performTouchInput {
                    swipe(center, Offset(center.x, center.y - 900f), durationMillis = 500)
                }
                composeRule.onNodeWithContentDescription("Ответить").assertIsNotEnabled()
                composeRule.onNodeWithContentDescription("Отклонить").assertIsNotEnabled()
                val dragTag = if (cycle % 2 == 0) "incoming_reply_handle" else "incoming_reply_cannot_talk"
                val expandedTop = composeRule.onNodeWithTag("incoming_reply_handle").fetchSemanticsNode().boundsInRoot.top
                composeRule.mainClock.autoAdvance = false
                composeRule.onNodeWithTag(dragTag).performTouchInput {
                    val start = center
                    val distance = collapsedTop - expandedTop + touchSlop - 0.25f
                    down(start)
                    repeat(20) { step ->
                        moveTo(Offset(start.x, start.y + distance * (step + 1) / 20f), delayMillis = 40)
                    }
                    advanceEventTime(200)
                    up()
                }
                // The panel has reached its rendered closed position, even if the
                // settling animation is still processing a fraction of a pixel.
                composeRule.mainClock.advanceTimeBy(32)
                composeRule.waitForIdle()
                assertEquals(collapsedTop,
                    composeRule.onNodeWithTag("incoming_reply_handle").fetchSemanticsNode().boundsInRoot.top)
                composeRule.onNodeWithTag("incoming_reply_scrim").assertDoesNotExist()
                composeRule.onNodeWithContentDescription("Ответить").assertIsEnabled()
                composeRule.onNodeWithContentDescription("Отклонить").assertIsEnabled()
                composeRule.mainClock.autoAdvance = true
                composeRule.waitForIdle()
            }
            assertEquals(0, answered)
            assertEquals(0, rejected)
            assertEquals(0, replies)
            composeRule.onNodeWithContentDescription("Ответить").performTouchInput {
                swipe(center, Offset(center.x, center.y - 420f), durationMillis = 500)
            }
            composeRule.waitForIdle()
            assertEquals(1, answered)
            assertEquals(0, rejected)
        } finally {
            composeRule.mainClock.autoAdvance = true
            activity.pause().stop().destroy()
        }
    }

    @Test
    fun swipeOpensWithoutRejectingAndSeparateTapSendsOnlyOnce() {
        val replies = mutableListOf<CallReplyCode>()
        var silences = 0
        var ordinaryRejects = 0
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        activity.get().setContent {
            TiniTalkTheme {
                IncomingCallScreen("call", "Alice", replySupported = true,
                    onReply = replies::add, onReplySheetExpanded = { silences++ },
                    onAnswer = {}, onReject = { ordinaryRejects++ })
            }
        }
        composeRule.onNodeWithTag("incoming_reply_handle").performTouchInput {
            swipe(center, Offset(center.x, center.y - 500f), durationMillis = 500)
        }
        composeRule.waitForIdle()
        assertEquals(emptyList<CallReplyCode>(), replies)
        assertEquals(0, ordinaryRejects)
        assertEquals(1, silences)
        composeRule.onNodeWithContentDescription("Отклонить").assertIsNotEnabled()
        composeRule.onNodeWithTag("incoming_reply_cannot_talk").performClick()
        composeRule.onNodeWithTag("incoming_reply_cannot_talk").performTouchInput { click() }
        assertEquals(listOf(CallReplyCode.CannotTalk), replies)
        activity.pause().stop().destroy()
    }

    @Test
    fun incompleteSwipeDoesNotSilenceAndScrimClosesWithoutRejecting() {
        var silences = 0
        var replies = 0
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        activity.get().setContent {
            TiniTalkTheme {
                IncomingCallScreen("call", "Alice", replySupported = true,
                    onReply = { replies++ }, onReplySheetExpanded = { silences++ },
                    onAnswer = {}, onReject = {})
            }
        }
        composeRule.onNodeWithTag("incoming_reply_handle").performTouchInput {
            swipe(center, Offset(center.x, center.y - 20f), durationMillis = 1000)
        }
        composeRule.waitForIdle()
        assertEquals(0, silences)
        composeRule.onNodeWithTag("incoming_reply_scrim").assertDoesNotExist()
        composeRule.onNodeWithTag("incoming_reply_handle").performSemanticsAction(SemanticsActions.OnClick) { it() }
        composeRule.waitForIdle()
        // The sheet overlaps the scrim's centre; tap the exposed backdrop above it.
        composeRule.onNodeWithTag("incoming_reply_scrim").performTouchInput { click(Offset(center.x, 10f)) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("incoming_reply_scrim").assertDoesNotExist()
        composeRule.onNodeWithTag("incoming_reply_handle").performSemanticsAction(SemanticsActions.OnClick) { it() }
        composeRule.waitForIdle()
        assertEquals(1, silences)
        assertEquals(0, replies)
        composeRule.onNodeWithTag("incoming_reply_cannot_talk").performTouchInput {
            swipe(center, Offset(center.x, center.y + 500f), durationMillis = 500)
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("incoming_reply_scrim").assertDoesNotExist()
        activity.pause().stop().destroy()
    }

    @Test
    fun capabilityHidesHandleAndNewCallResetsOpenPanel() {
        val supported = mutableStateOf(false)
        val callId = mutableStateOf("one")
        var rejected = 0
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        activity.get().setContent {
            TiniTalkTheme {
                IncomingCallScreen(callId.value, "Alice", replySupported = supported.value,
                    onReply = {}, onReplySheetExpanded = {}, onAnswer = {}, onReject = { rejected++ })
            }
        }
        composeRule.onNodeWithTag("incoming_reply_handle").assertDoesNotExist()
        composeRule.runOnIdle { supported.value = true }
        composeRule.onNodeWithTag("incoming_reply_handle").performSemanticsAction(SemanticsActions.OnClick) { it() }
        composeRule.waitForIdle()
        composeRule.runOnIdle { callId.value = "two" }
        composeRule.onNodeWithTag("incoming_reply_scrim").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Отклонить").performSemanticsAction(SemanticsActions.OnClick) { it() }
        assertEquals(1, rejected)
        activity.pause().stop().destroy()
    }
}
