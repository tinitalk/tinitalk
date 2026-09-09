package org.tinitalk.ui.call

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.tinitalk.call.CallDirection
import org.tinitalk.call.CallEndReason
import org.tinitalk.call.CallReplyCode
import org.tinitalk.ui.theme.TiniTalkTheme

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w384dp-h853dp-mdpi")
class EndedCallScreenTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun failedAttemptExplainsContactRestrictionBelowName() {
        val reason = mutableStateOf(CallEndReason.NotInContacts)
        val duration = mutableStateOf<String?>(null)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        activity.get().setContent {
            TiniTalkTheme {
                EndedCallScreen("Тест Тоби", reason.value, direction = CallDirection.Outgoing,
                    durationText = duration.value)
            }
        }
        try {
            compose.onNodeWithText("Не удалось связаться").assertIsDisplayed()
            val name = compose.onNodeWithText("Тест Тоби").fetchSemanticsNode().boundsInRoot
            val explanation = compose.onNodeWithText("Вас ещё не добавили в контакты")
                .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue(explanation.top > name.bottom)
            compose.onAllNodes(hasClickAction()).assertCountEquals(0)
            for (failure in listOf(CallEndReason.Failed, CallEndReason.ConnectionLost)) {
                compose.runOnIdle { reason.value = failure }
                compose.onNodeWithText("Не удалось связаться").assertIsDisplayed()
                compose.onNodeWithText("Вас ещё не добавили в контакты").assertDoesNotExist()
            }
            compose.runOnIdle { duration.value = "01:05" }
            compose.onNodeWithText("Звонок завершён").assertIsDisplayed()
            compose.onNodeWithText("01:05").assertIsDisplayed()
            compose.onNodeWithText("Не удалось связаться").assertDoesNotExist()
        } finally { activity.pause().stop().destroy() }
    }

    @Test fun replyKeepsEndedCallIdentityAndAddsOnlyMessageBelowName() {
        val reply = mutableStateOf<CallReplyCode?>(null)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        activity.get().setContent {
            TiniTalkTheme {
                EndedCallScreen("Тест Тоби", CallEndReason.Rejected, reply = reply.value,
                    direction = CallDirection.Outgoing)
            }
        }
        try {
            val statusBounds = compose.onNodeWithText("Звонок отклонён").fetchSemanticsNode().boundsInRoot
            val avatarBounds = compose.onNodeWithTag("call-peer-avatar").fetchSemanticsNode().boundsInRoot
            val nameBounds = compose.onNodeWithText("Тест Тоби").fetchSemanticsNode().boundsInRoot
            compose.onNodeWithTag("call_reply_result").assertDoesNotExist()
            val replies = mapOf(
                CallReplyCode.CannotTalk to "сейчас не может говорить",
                CallReplyCode.CallMeLater to "просит перезвонить позже",
                CallReplyCode.WillCallBack to "обещает перезвонить позже",
            )
            for ((code, text) in replies) {
                compose.runOnIdle { reply.value = code }
                assertEquals(statusBounds, compose.onNodeWithText("Звонок отклонён").fetchSemanticsNode().boundsInRoot)
                assertEquals(avatarBounds, compose.onNodeWithTag("call-peer-avatar").fetchSemanticsNode().boundsInRoot)
                assertEquals(nameBounds, compose.onNodeWithText("Тест Тоби").fetchSemanticsNode().boundsInRoot)
                val message = compose.onNodeWithTag("call_reply_result").assertTextEquals(text)
                    .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                assertTrue(message.top > nameBounds.bottom)
                compose.onAllNodes(hasClickAction()).assertCountEquals(0)
            }
        } finally { activity.pause().stop().destroy() }
    }

    @Test @Config(qualifiers = "w360dp-h640dp-mdpi")
    fun longNameAndLargeTextLeaveReplyReadableWithoutDismissButton() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        activity.get().setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 1.8f)) {
                TiniTalkTheme {
                    EndedCallScreen("Александр Константинопольский", CallEndReason.Rejected,
                        reply = CallReplyCode.CallMeLater, direction = CallDirection.Outgoing)
                }
            }
        }
        try {
            compose.onNodeWithText("Звонок отклонён").assertIsDisplayed()
            compose.onNodeWithTag("call_reply_result").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("просит перезвонить позже").assertIsDisplayed()
            compose.onAllNodes(hasClickAction()).assertCountEquals(0)
        } finally { activity.pause().stop().destroy() }
    }
}
