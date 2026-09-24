package org.tinitalk.ui.call

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.tinitalk.R
import org.tinitalk.call.AccountCallOwner
import org.tinitalk.call.CallSessionBinding
import org.tinitalk.call.WaitingCall
import org.tinitalk.call.WaitingCallsState
import org.tinitalk.data.AccountId
import org.tinitalk.i18n.LocalizedTestApplication
import org.tinitalk.i18n.appString
import org.tinitalk.push.IncomingInvite
import org.tinitalk.ui.theme.TiniTalkTheme
import java.io.File
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = LocalizedTestApplication::class, sdk = [35], qualifiers = "ru-w360dp-h800dp-mdpi")
class WaitingCallsPanelTest {
    @get:Rule val compose = createEmptyComposeRule()

    private var activity: ActivityController<ComponentActivity>? = null
    private val state = mutableStateOf(WaitingCallsState())
    private val answered = mutableListOf<AccountCallOwner>()
    private val rejected = mutableListOf<AccountCallOwner>()
    private val replies = mutableListOf<Pair<AccountCallOwner, org.tinitalk.call.CallReplyCode>>()
    private val answer get() = appString(R.string.text_answer_64)
    private val busy get() = appString(R.string.text_busy_91)

    @After fun cleanup() {
        activity?.pause()?.stop()?.destroy()
    }

    @Test fun envelopeOpensChoicesAndRepliesToOnlySelectedCaller() {
        val first = pending("First")
        val second = pending("Second", server = "two")
        render(listOf(first, second))
        compose.onAllNodesWithContentDescription(appString(R.string.call_reply_sheet_title))[1].performClick()
        assertTrue(replies.isEmpty())
        assertTrue(rejected.isEmpty())
        val reply = org.tinitalk.call.CallReplyCode.WillCallBack
        compose.onNodeWithText(appString(reply.textRes)).performClick()
        assertEquals(listOf(second.invite.owner to reply), replies)
        assertTrue(answered.isEmpty())
        assertTrue(rejected.isEmpty())
        compose.onNodeWithText(appString(reply.textRes)).assertDoesNotExist()
    }

    @Test fun disappearingCallerClosesReplyChoicesWithoutSending() {
        val first = pending("First")
        render(listOf(first))
        compose.onAllNodesWithContentDescription(appString(R.string.call_reply_sheet_title))[0].performClick()
        compose.onNodeWithText(appString(R.string.call_reply_will_call_back)).assertIsDisplayed()
        compose.runOnIdle { state.value = WaitingCallsState() }
        compose.onNodeWithText(appString(R.string.call_reply_will_call_back)).assertDoesNotExist()
        assertTrue(replies.isEmpty())
        assertTrue(rejected.isEmpty())
    }

    @Test fun replyHeaderSeparatesRecipientFromActionAndChoices() {
        checkReplyHeader(fontScale = 1f)
    }

    @Test fun replyHeaderWrapsLongRecipientAndTitleWithLargeText() {
        checkReplyHeader(fontScale = 2f)
    }

    private fun checkReplyHeader(fontScale: Float) {
        org.robolectric.RuntimeEnvironment.setFontScale(fontScale)
        val name = "Александра Константиновна Петрова"
        render(listOf(pending(name)), fontScale = fontScale)
        compose.onAllNodesWithContentDescription(appString(R.string.call_reply_sheet_title))[0].performClick()
        val header = hasAnyAncestor(hasTestTag("waiting-reply-header"))
        val recipient = compose.onNode(header and hasText(name)).assertIsDisplayed()
        val title = compose.onNode(header and hasText(appString(R.string.call_reply_sheet_title))).assertIsDisplayed()
        val image = compose.onNodeWithTag("waiting-reply-content").captureToImage().asAndroidBitmap()
        val directory = File("build/outputs/waiting-panel").apply { mkdirs() }
        File(directory, "reply-$fontScale.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        for (label in listOf(recipient, title)) {
            val layouts = mutableListOf<TextLayoutResult>()
            label.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            assertFalse(layout.didOverflowHeight)
            for (line in 0 until layout.lineCount) {
                assertFalse(layout.isLineEllipsized(line))
                assertTrue(layout.getLineRight(line) - layout.getLineLeft(line) <= layout.size.width + 1f)
            }
        }
        assertTrue(recipient.fetchSemanticsNode().boundsInRoot.bottom < title.fetchSemanticsNode().boundsInRoot.top)
        val choice = compose.onNodeWithText(appString(R.string.call_reply_will_call_back)).assertIsDisplayed()
        assertTrue(title.fetchSemanticsNode().boundsInRoot.bottom < choice.fetchSemanticsNode().boundsInRoot.top)
    }

    @Test fun longNameUsesTwoLinesAboveEqualSizedButtons() {
        val name = "Александра Константиновна Петрова"
        render(listOf(pending(name)))

        val layouts = mutableListOf<TextLayoutResult>()
        val label = compose.onNodeWithText(name).assertIsDisplayed()
        label.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(2, layouts.single().lineCount)
        assertFalse(layouts.single().isLineEllipsized(1))
        val nameBounds = label.fetchSemanticsNode().boundsInRoot
        val busyBounds = compose.onNodeWithText(busy).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val answerBounds = compose.onNodeWithText(answer).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(nameBounds.bottom < busyBounds.top)
        assertTrue(nameBounds.width > busyBounds.width)
        assertEquals(busyBounds.top, answerBounds.top, 0.5f)
        assertEquals(busyBounds.width, answerBounds.width, 0.5f)
        assertEquals(busyBounds.height, answerBounds.height, 0.5f)
        assertTrue(busyBounds.height >= 48f)
        savePreview("long-name")
    }

    @Test fun largeFontKeepsButtonsReachableAndLimitsVeryLongNameToTwoLines() {
        val call = pending("Очень длинное имя контакта, которое не должно занимать всю панель")
        render(listOf(call), fontScale = 2f)

        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(call.invite.caller).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(2, layouts.single().lineCount)
        assertTrue(layouts.single().isLineEllipsized(1))
        val answerLayouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(answer, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(answerLayouts) }
        assertEquals("The answer label should fit without splitting the word", 1, answerLayouts.single().lineCount)
        val answerLayout = answerLayouts.single()
        assertFalse("The answer label must not be clipped vertically", answerLayout.didOverflowHeight)
        // Center alignment offsets the line within the paragraph; compare its
        // actual width, not its right coordinate, with the measured text box.
        assertTrue("The full answer label must remain visible",
            answerLayout.getLineRight(0) - answerLayout.getLineLeft(0) <= answerLayout.size.width + 1f)
        compose.onNodeWithText(answer).performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText(busy).performScrollTo().assertIsDisplayed().performClick()
        assertEquals(listOf(call.invite.owner), answered)
        assertEquals(listOf(call.invite.owner), rejected)
        val answerBounds = compose.onNodeWithText(answer).fetchSemanticsNode().boundsInRoot
        val busyBounds = compose.onNodeWithText(busy).fetchSemanticsNode().boundsInRoot
        assertEquals(busyBounds.height, answerBounds.height, 0.5f)
        assertTrue(answerBounds.height >= 48f)
        savePreview("large-font")
    }

    @Test fun unacknowledgedCallCanBeRejectedButNotAnswered() {
        val call = pending("Мама").copy(acknowledged = false)
        render(listOf(call))
        compose.onNodeWithText(answer).assertIsNotEnabled()
        compose.onNodeWithText(busy).assertIsEnabled().performClick()
        assertEquals(listOf(call.invite.owner), rejected)

        compose.runOnIdle { state.value = state.value.copy(calls = listOf(call.copy(acknowledged = true))) }
        compose.onNodeWithText(answer).assertIsEnabled().performClick()
        assertEquals(listOf(call.invite.owner), answered)
    }

    @Test fun bothWaitingCallActionsAreFullyVisibleWithoutScrolling() {
        checkTwoCallsVisible(fontScale = 1f)
        savePreview("two-calls")
    }

    @Test fun twoCallsAlsoFitWithLargerText() {
        checkTwoCallsVisible(fontScale = 1.5f)
        savePreview("two-calls-large-font")
    }

    private fun checkTwoCallsVisible(fontScale: Float) {
        val calls = listOf(
            pending("Александра Константиновна Петрова", server = "one"),
            pending("Валентина Александровна", server = "two"),
        )
        render(calls, fontScale = fontScale)
        calls.indices.forEach { index ->
            compose.onNodeWithText(calls[index].invite.caller).assertIsDisplayed()
            val rejectButton = compose.onAllNodesWithText(busy)[index].assertIsDisplayed()
            val answerButton = compose.onAllNodesWithText(answer)[index].assertIsDisplayed()
            // Clipped semantics bounds must still include the full touch target.
            assertTrue(rejectButton.fetchSemanticsNode().boundsInRoot.height >= 48f)
            assertTrue(answerButton.fetchSemanticsNode().boundsInRoot.height >= 48f)
            rejectButton.performClick()
            answerButton.performClick()
        }
        assertEquals(calls.map { it.invite.owner }, rejected)
        assertEquals(calls.map { it.invite.owner }, answered)
    }

    @Test fun shortScreenKeepsLastCallReachableByScrolling() {
        val calls = (1..3).map { pending("Контакт $it", server = "server$it") }
        render(calls, availableHeight = 320.dp)
        assertTrue(compose.onRoot().fetchSemanticsNode().boundsInRoot.height <= 320f * 0.65f + 1f)
        compose.onAllNodesWithText(answer)[2].performScrollTo().assertIsDisplayed().performClick()
        compose.onAllNodesWithText(busy)[2].performScrollTo().assertIsDisplayed().performClick()
        assertEquals(listOf(calls.last().invite.owner), answered)
        assertEquals(listOf(calls.last().invite.owner), rejected)
        savePreview("short-screen")
    }

    @Test fun acceptingCallDisablesBothActionsUntilSwitchCompletes() {
        val call = pending("Мама")
        render(listOf(call))
        compose.runOnIdle { state.value = state.value.copy(answering = call.invite.owner) }
        compose.onNodeWithText(answer).assertIsNotEnabled().performClick()
        compose.onNodeWithText(busy).assertIsNotEnabled().performClick()
        assertTrue(answered.isEmpty())
        assertTrue(rejected.isEmpty())
    }

    @Test fun allWaitingCallsRemainReachableWithOnlyOneSharedExplanation() {
        val calls = (1..8).map { pending("Контакт $it", server = "server$it") }
        render(calls)
        compose.onNodeWithText(appString(R.string.waiting_answer_ends_current)).assertExists()
        savePreview("multiple-calls")
        compose.onAllNodesWithText(busy)[7].performScrollTo().assertIsDisplayed().performClick()
        compose.onAllNodesWithText(answer)[7].performScrollTo().assertIsDisplayed().performClick()
        assertEquals(listOf(calls.last().invite.owner), rejected)
        assertEquals(listOf(calls.last().invite.owner), answered)
    }

    @Test fun removingCallPreservesOtherServersActionsAndEmptyPanelDisappears() {
        // Identical names and call IDs on separate servers must not mix up buttons.
        val first = pending("Мама", server = "one")
        val second = pending("Мама", server = "two")
        render(listOf(first, second))
        compose.runOnIdle { state.value = state.value.copy(calls = listOf(second)) }
        compose.onNodeWithText(busy).performClick()
        compose.onNodeWithText(answer).performClick()
        assertEquals(listOf(second.invite.owner), rejected)
        assertEquals(listOf(second.invite.owner), answered)

        compose.runOnIdle { state.value = WaitingCallsState() }
        compose.onNodeWithText(appString(R.string.waiting_calls_title)).assertDoesNotExist()
        compose.onNodeWithText(answer).assertDoesNotExist()
        compose.onNodeWithText(busy).assertDoesNotExist()
    }

    private fun render(calls: List<WaitingCall>, fontScale: Float = 1f, availableHeight: Dp = 800.dp) {
        state.value = WaitingCallsState(calls)
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().also { controller ->
            controller.get().setContent {
                TiniTalkTheme(darkTheme = true) {
                    CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                        WaitingCallsPanel(state.value, onAnswer = { answered += it }, onReject = { rejected += it },
                            onReply = { owner, code -> replies += owner to code },
                            modifier = Modifier.width(320.dp).heightIn(max = availableHeight))
                    }
                }
            }
        }
    }

    private fun pending(name: String, server: String = "one") = WaitingCall(
        invite = IncomingInvite(
            AccountId(server), CallSessionBinding("https://$server.example", "me", "session", null),
            "same-call-id", name, Instant.parse("2026-09-24T12:00:45Z"), callerLogin = "caller",
        ),
        deadlineElapsedMs = 15_000,
        originalDeadlineElapsedMs = 45_000,
        acknowledged = true,
    )

    private fun savePreview(name: String) {
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val directory = File("build/outputs/waiting-panel").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
