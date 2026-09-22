package org.tinitalk.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.tinitalk.data.Contact
import org.tinitalk.data.ContactAddress
import org.tinitalk.data.CallHistoryItem
import org.tinitalk.ui.theme.TiniTalkTheme

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ru-w320dp-h720dp")
class ContactScreenScrollingTest {
    @get:Rule val composeRule = createEmptyComposeRule()

    @Test fun shortHistoryKeepsScrollPositionWithLargeText() = checkShortHistory(2f)
    @Test fun swipeFromAvatarScrollsTheContact() = checkShortHistory(1f, true)

    private fun checkShortHistory(fontScale: Float, avatarSwipe: Boolean = false) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        composeRule.runOnUiThread {
            activity.get().setContent {
                TiniTalkTheme(darkTheme = true) {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                        ContactScreen(
                            contact = Contact(login = "alex", displayName = "Александра Константинопольская"),
                            contactAddress = ContactAddress.of("https://example.com", "alex"),
                            nameUpdate = ContactNameUpdateState(),
                            history = ContactHistoryState(loaded = true, items = if (!avatarSwipe) emptyList() else List(30) { index ->
                                CallHistoryItem(id = (index + 1).toLong(), peerLogin = "alex", peerName = "Александра",
                                    direction = "incoming", outcome = "completed", reached = true,
                                    startedAt = 1_788_400_000L - index * 60, durationSeconds = 30)
                            }),
                            ongoingCall = null,
                            onBack = {}, onCall = {}, onOpenCall = {}, onRename = {},
                            onRenameHandled = {}, onLoadMoreHistory = {}, onRetryHistory = {},
                        )
                    }
                }
            }
        }
        val list = composeRule.onNode(hasScrollAction())
        val before = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        val gestureNode = if (avatarSwipe) composeRule.onNodeWithTag("contact-profile-avatar") else list
        gestureNode.performTouchInput {
            val start = centerY + 40f
            down(Offset(if (avatarSwipe) centerX else 10f, start))
            for (i in 1..10) moveTo(Offset(if (avatarSwipe) centerX else 10f, start - i * 10f), 100L)
            advanceEventTime(200L)
        }
        val middle = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        gestureNode.performTouchInput { up() }
        composeRule.waitForIdle()
        val after = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        if (avatarSwipe) {
            list.performTouchInput {
                swipeUp(startY = centerY + 60f, endY = centerY - 90f, durationMillis = 1_000)
            }
            val control = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            assertTrue("Control swipe below the avatar must work", control > 20f)
        } else {
            assertTrue("The card must actually be scrollable in this reproduction", middle > 20f)
        }
        assertTrue("Slow scrolling a short contact card must not bounce back to its start: $before -> $after", after > before + 20f)
    }
}
