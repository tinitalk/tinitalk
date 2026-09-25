package org.tinitalk.ui.call

import org.tinitalk.R
import org.tinitalk.i18n.appString

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
@Config(sdk = [35], qualifiers = "ru-w384dp-h853dp-mdpi")
class EndedCallScreenTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test @Config(qualifiers = "ru-w640dp-h360dp-land-mdpi")
    fun landscapeCallResultsAreCenteredOnTheRightWithoutMovingIdentity() {
        val reason = mutableStateOf(CallEndReason.Busy)
        val reply = mutableStateOf<CallReplyCode?>(null)
        val duration = mutableStateOf<String?>(null)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        activity.get().setContent {
            TiniTalkTheme {
                EndedCallScreen("Alice", reason.value, reply = reply.value,
                    direction = CallDirection.Outgoing, durationText = duration.value)
            }
        }
        try {
            val identity = compose.onNodeWithTag("landscape-identity-panel").fetchSemanticsNode().boundsInRoot
            val initialAvatar = compose.onNodeWithTag("call-peer-avatar").fetchSemanticsNode().boundsInRoot
            val initialName = compose.onNodeWithText("Alice").fetchSemanticsNode().boundsInRoot
            val cases = listOf(
                CallEndReason.Busy to R.string.text_busy_91,
                CallEndReason.Failed to R.string.text_could_not_connect_192,
                CallEndReason.Rejected to R.string.text_call_declined_193,
                CallEndReason.TimedOut to R.string.text_no_answer_194,
                CallEndReason.NotInContacts to R.string.text_could_not_connect_192,
                CallEndReason.RemoteHangup to R.string.text_call_ended_93,
            )
            for ((endReason, message) in cases) {
                compose.runOnIdle {
                    reason.value = endReason
                    reply.value = if (endReason == CallEndReason.Rejected) CallReplyCode.CallMeLater else null
                }
                val info = compose.onNodeWithTag("landscape-call-info").fetchSemanticsNode().boundsInRoot
                val status = compose.onNodeWithTag("landscape-call-status").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                val text = compose.onNodeWithText(appString(message)).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                assertTrue(text.left >= identity.right)
                assertEquals(info.center.x, status.center.x, 1f)
                assertEquals(info.center.y, status.center.y, 1f)
                assertEquals(initialAvatar, compose.onNodeWithTag("call-peer-avatar").fetchSemanticsNode().boundsInRoot)
                assertEquals(initialName, compose.onNodeWithText("Alice").fetchSemanticsNode().boundsInRoot)
                for (tag in listOf("call_reply_result", "call_end_explanation")) {
                    compose.onAllNodesWithTag(tag).fetchSemanticsNodes().forEach {
                        assertTrue(it.boundsInRoot.left >= identity.right)
                        assertTrue(it.boundsInRoot.top >= text.bottom)
                    }
                }
                compose.onAllNodes(hasClickAction()).assertCountEquals(0)
            }
            compose.runOnIdle { duration.value = "02:15" }
            val timer = compose.onNodeWithText("02:15").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue(timer.right <= identity.right)
        } finally { activity.pause().stop().destroy() }
    }

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
            compose.onNodeWithText(appString(R.string.text_could_not_connect_192)).assertIsDisplayed()
            val name = compose.onNodeWithText("Тест Тоби").fetchSemanticsNode().boundsInRoot
            val explanation = compose.onNodeWithText(appString(R.string.text_you_have_not_been_added_to_contacts_yet_92))
                .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue(explanation.top > name.bottom)
            compose.onAllNodes(hasClickAction()).assertCountEquals(0)
            for (failure in listOf(CallEndReason.Failed, CallEndReason.ConnectionLost)) {
                compose.runOnIdle { reason.value = failure }
                compose.onNodeWithText(appString(R.string.text_could_not_connect_192)).assertIsDisplayed()
                compose.onNodeWithText(appString(R.string.text_you_have_not_been_added_to_contacts_yet_92)).assertDoesNotExist()
            }
            compose.runOnIdle { duration.value = "01:05" }
            compose.onNodeWithText(appString(R.string.text_call_ended_93)).assertIsDisplayed()
            compose.onNodeWithText("01:05").assertIsDisplayed()
            compose.onNodeWithText(appString(R.string.text_could_not_connect_192)).assertDoesNotExist()
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
            val statusBounds = compose.onNodeWithText(appString(R.string.text_call_declined_193)).fetchSemanticsNode().boundsInRoot
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
                assertEquals(statusBounds, compose.onNodeWithText(appString(R.string.text_call_declined_193)).fetchSemanticsNode().boundsInRoot)
                assertEquals(avatarBounds, compose.onNodeWithTag("call-peer-avatar").fetchSemanticsNode().boundsInRoot)
                assertEquals(nameBounds, compose.onNodeWithText("Тест Тоби").fetchSemanticsNode().boundsInRoot)
                val message = compose.onNodeWithTag("call_reply_result").assertTextEquals(text)
                    .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                assertTrue(message.top > nameBounds.bottom)
                compose.onAllNodes(hasClickAction()).assertCountEquals(0)
            }
        } finally { activity.pause().stop().destroy() }
    }

    @Test @Config(qualifiers = "ru-w360dp-h640dp-mdpi")
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
            compose.onNodeWithText(appString(R.string.text_call_declined_193)).assertIsDisplayed()
            compose.onNodeWithTag("call_reply_result").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(appString(R.string.call_reply_result_call_me_later)).assertIsDisplayed()
            compose.onAllNodes(hasClickAction()).assertCountEquals(0)
        } finally { activity.pause().stop().destroy() }
    }
}
