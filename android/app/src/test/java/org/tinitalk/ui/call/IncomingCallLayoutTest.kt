package org.tinitalk.ui.call

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.Density
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.tinitalk.ui.theme.TiniTalkTheme

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-mdpi")
class IncomingCallLayoutTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun smallScreenKeepsButtonsAboveReplyHandle() = checkLayout()
    @Test fun largeTextKeepsButtonsAboveReplyHandle() = checkLayout(fontScale = 1.8f)
    @Test @Config(qualifiers = "w360dp-h800dp-mdpi")
    fun regularScreenKeepsButtonsAboveReplyHandle() = checkLayout()
    @Test fun ordinaryAnswerSwipeStillAnswersOnSmallScreen() = checkGesture("Ответить")
    @Test fun ordinaryRejectSwipeStillRejectsOnSmallScreen() = checkGesture("Отклонить")

    @Test fun halosAnimateTogetherWithoutFadingButtons() {
        // Automatic idling cancels infinite animations; own the clock before composition.
        compose.mainClock.autoAdvance = false
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        activity.get().setContent {
            TiniTalkTheme {
                IncomingCallScreen("call", "Alice", onAnswer = {}, onReject = {})
            }
        }
        try {
            compose.waitForIdle()
            fun sample(label: String, offset: Int) =
                compose.onNodeWithContentDescription(label).captureToImage().toPixelMap().let {
                    it[it.width / 2 + offset, it.height / 2]
                }
            val buttons = listOf("Ответить", "Отклонить")
            val initialColors = buttons.map { sample(it, 25) }
            val halos = mutableListOf<Float>()
            repeat(24) {
                compose.mainClock.advanceTimeBy(100)
                val left = sample(buttons[0], 44)
                val right = sample(buttons[1], 44)
                assertEquals("Both halos must share a rhythm", left.red, right.red, 0.01f)
                halos += left.red
                buttons.forEachIndexed { index, label ->
                    assertEquals("The button must stay opaque", initialColors[index], sample(label, 25))
                }
            }
            assertTrue("The halo must appear and disappear instead of staying static: $halos; lifecycle=${activity.get().lifecycle.currentState}",
                halos.max() - halos.min() > 0.025f)
        } finally {
            compose.mainClock.autoAdvance = true
            activity.pause().stop().destroy()
        }
    }

    @Test fun pulseStopsWhileHiddenAndResumesAfterScreenOrPanelReturns() {
        compose.mainClock.autoAdvance = false
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val enabled = mutableStateOf(true)
        lateinit var pulse: State<Float>
        activity.get().setContent { pulse = rememberIncomingCallPulse(enabled.value) }
        try {
            compose.waitForIdle()
            fun assertMoving() {
                compose.waitForIdle()
                compose.mainClock.advanceTimeBy(200)
                val before = pulse.value
                compose.mainClock.advanceTimeBy(200)
                assertNotEquals("Visible buttons must animate", before, pulse.value)
            }
            fun assertHidden() {
                compose.waitForIdle()
                compose.mainClock.advanceTimeBy(200)
                assertTrue("Hidden pulse must be fully faded", pulse.value >= 1f)
                val before = pulse.value
                compose.mainClock.advanceTimeBy(600)
                assertEquals("Hidden pulse must not keep running", before, pulse.value)
            }
            assertMoving()
            compose.runOnUiThread { enabled.value = false }
            assertHidden()
            compose.runOnUiThread { enabled.value = true }
            assertMoving()
            compose.runOnUiThread { activity.pause() }
            assertHidden()
            compose.runOnUiThread { activity.resume() }
            assertMoving()
        } finally {
            compose.mainClock.autoAdvance = true
            activity.pause().stop().destroy()
        }
    }

    private fun checkLayout(fontScale: Float = 1f) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        activity.get().setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                TiniTalkTheme {
                    IncomingCallScreen("call", "Alice", replySupported = true, onAnswer = {}, onReject = {})
                }
            }
        }
        try {
            val handle = compose.onNodeWithTag("incoming_reply_handle").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            for (label in listOf("Ответить", "Отклонить")) {
                val button = compose.onNodeWithContentDescription(label).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                assertTrue("$label needs a separate touch area above reply handle: $button / $handle",
                    button.bottom + 32f <= handle.top)
            }
            compose.onNodeWithText("Alice").assertIsDisplayed()
        } finally { activity.pause().stop().destroy() }
    }

    private fun checkGesture(label: String) {
        var answered = 0
        var rejected = 0
        var expanded = 0
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        activity.get().setContent {
            TiniTalkTheme {
                IncomingCallScreen("call", "Alice", replySupported = true,
                    onReplySheetExpanded = { expanded++ }, onAnswer = { answered++ }, onReject = { rejected++ })
            }
        }
        try {
            compose.onNodeWithContentDescription(label).performTouchInput {
                swipe(center, Offset(center.x, center.y - 160f), durationMillis = 500)
            }
            compose.waitForIdle()
            assertEquals(if (label == "Ответить") 1 else 0, answered)
            assertEquals(if (label == "Отклонить") 1 else 0, rejected)
            assertEquals(0, expanded)
        } finally { activity.pause().stop().destroy() }
    }
}
