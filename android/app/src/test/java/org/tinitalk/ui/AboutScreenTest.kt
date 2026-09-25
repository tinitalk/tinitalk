package org.tinitalk.ui

import org.tinitalk.R
import org.tinitalk.i18n.appString

import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import org.tinitalk.data.ServerCheckDetails
import org.tinitalk.data.ServerCheckResult
import org.tinitalk.ui.theme.TiniTalkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [35],
    qualifiers = "ru-w240dp-h1000dp",
)
class AboutScreenTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    @Config(qualifiers = "ru-w360dp-h640dp-port-mdpi")
    fun portraitLanguageScrollbarIsVisibleBeforeScrollingAndTracksPosition() = checkLanguageScrollbar(true)

    @Test
    @Config(qualifiers = "ru-w800dp-h360dp-land-mdpi")
    fun landscapeLanguageScrollbarIsVisibleBeforeScrollingAndTracksPosition() = checkLanguageScrollbar(true)

    @Test
    @Config(qualifiers = "ru-w360dp-h1400dp-port-mdpi")
    fun languageScrollbarIsHiddenWhenAllLanguagesFit() = checkLanguageScrollbar(false)

    private fun checkLanguageScrollbar(overflows: Boolean) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        var background = Color.Unspecified
        var track = Color.Unspecified
        var thumb = Color.Unspecified
        composeRule.runOnUiThread {
            activity.get().setContent {
                TiniTalkTheme(darkTheme = true) {
                    background = MaterialTheme.colorScheme.surface
                    val color = MaterialTheme.colorScheme.onSurfaceVariant
                    track = color.copy(alpha = 0.12f).compositeOver(background)
                    thumb = color.copy(alpha = 0.65f).compositeOver(track)
                    AboutScreen(serverUrl = "", onCheckServer = { error("No server configured") }, onBack = {})
                }
            }
        }
        try {
            composeRule.onNodeWithTag("about-language").performClick()
            val list = composeRule.onNodeWithTag("language-list")
            val range = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
            assertEquals(overflows, range.maxValue() > 0f)
            fun assertPixel(expected: Color, actual: Color) {
                assertEquals(expected.red, actual.red, 2f / 255f)
                assertEquals(expected.green, actual.green, 2f / 255f)
                assertEquals(expected.blue, actual.blue, 2f / 255f)
            }
            val before = list.captureToImage().toPixelMap()
            if (overflows) {
                assertPixel(thumb, before[before.width - 6, 21])
                assertPixel(track, before[before.width - 6, before.height - 21])
                list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, range.maxValue()) }
                composeRule.waitForIdle()
                assertEquals(range.maxValue(), range.value(), 1f)
                val after = list.captureToImage().toPixelMap()
                assertPixel(track, after[after.width - 6, 21])
                assertPixel(thumb, after[after.width - 6, after.height - 21])
            } else {
                for (y in listOf(21, before.height / 2, before.height - 21)) {
                    assertPixel(background, before[before.width - 6, y])
                }
            }
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test
    @Config(qualifiers = "ru-w240dp-h1000dp-port-mdpi")
    fun narrowLanguageFieldHasRightChevronAndOpensPicker() = checkLanguageChevron()

    @Test
    @Config(qualifiers = "ru-w800dp-h360dp-land-mdpi")
    fun landscapeLanguageFieldHasRightChevronAndOpensPicker() = checkLanguageChevron()

    private fun checkLanguageChevron() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        var back = false
        composeRule.runOnUiThread {
            activity.get().setContent {
                TiniTalkTheme(darkTheme = true) {
                    AboutScreen(serverUrl = "", onCheckServer = { error("No server configured") }, onBack = { back = true })
                }
            }
        }
        try {
            val field = composeRule.onNodeWithTag("about-language").assertHasClickAction().fetchSemanticsNode().boundsInRoot
            val chevronNode = composeRule.onNodeWithTag("about-language-chevron", useUnmergedTree = true)
                .assertIsDisplayed().assertHasNoClickAction()
            val chevron = chevronNode.fetchSemanticsNode().boundsInRoot
            val title = composeRule.onNodeWithText(appString(R.string.language_title), useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            assertEquals(48f, chevron.width, 1f)
            assertEquals(48f, chevron.height, 1f)
            assertEquals(field.right - 20f, chevron.right, 1f)
            assertEquals(field.center.y, chevron.center.y, 1f)
            assertTrue(title.right <= chevron.left - 12f)
            // The arrow is decorative; tapping it activates the whole field, not a second button.
            chevronNode.performTouchInput { click() }
            val inPicker = hasAnyAncestor(hasTestTag("language-picker"))
            composeRule.onNodeWithTag("language-picker").assertIsDisplayed()
            composeRule.onNodeWithText(appString(R.string.text_cancel_12)).assertDoesNotExist()
            composeRule.onAllNodes(hasText(appString(R.string.language_title)) and inPicker).assertCountEquals(0)
            // Back must close the modal without leaving About.
            composeRule.runOnUiThread {
                (ShadowDialog.getLatestDialog() as ComponentDialog).onBackPressedDispatcher.onBackPressed()
            }
            composeRule.onNodeWithTag("language-picker").assertDoesNotExist()
            assertEquals(false, back)
            composeRule.onNodeWithTag("about-language").performClick()
            composeRule.onNodeWithTag("language-picker").assertIsDisplayed()
            composeRule.runOnUiThread {
                val dialog = ShadowDialog.getLatestDialog()
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(0, 16, action, -100f, -100f, 0)
                    try {
                        dialog.onTouchEvent(event)
                    } finally {
                        event.recycle()
                    }
                }
            }
            composeRule.onNodeWithTag("language-picker").assertDoesNotExist()
            assertEquals(false, back)
            composeRule.onNodeWithTag("about-language").performClick()
            // Selecting the current language still closes the list, without a confirmation step.
            composeRule.onNode(isSelected() and inPicker).performClick()
            composeRule.onNodeWithTag("language-picker").assertDoesNotExist()
            composeRule.onNodeWithTag("about-language").assertIsDisplayed()
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test
    @Config(qualifiers = "ru-w800dp-h360dp-land-mdpi")
    fun landscapeHeaderStaysInsideFullHeightIdentityPanelWithSharedBackgroundAndDivider() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        var panelColor = Color.Unspecified
        var dividerColor = Color.Unspecified
        var back = false
        composeRule.runOnUiThread {
            activity.get().setContent {
                TiniTalkTheme(darkTheme = true) {
                    panelColor = MaterialTheme.colorScheme.surface
                    dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f).compositeOver(panelColor)
                    AboutScreen(
                        serverUrl = "https://talk.example.com",
                        onCheckServer = { ServerCheckDetails(ServerCheckResult.Available, apiVersion = 2) },
                        onBack = { back = true },
                    )
                }
            }
        }
        try {
            val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
            val panelNode = composeRule.onNodeWithTag("landscape-identity-panel")
            val panel = panelNode.fetchSemanticsNode().boundsInRoot
            val headerNode = composeRule.onNodeWithTag("about-header")
            val header = headerNode.fetchSemanticsNode().boundsInRoot
            val details = composeRule.onNodeWithTag("about-details").fetchSemanticsNode().boundsInRoot
            val title = composeRule.onNodeWithText(appString(R.string.text_about_102)).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertEquals(root.left, panel.left, 1f)
            assertEquals(root.top, panel.top, 1f)
            assertEquals(root.bottom, panel.bottom, 1f)
            assertEquals(root.width * LandscapeIdentityPaneWeight, panel.width, 1f)
            assertEquals(panel.left, header.left, 1f)
            assertEquals(panel.right, header.right, 1f)
            assertTrue(title.left >= panel.left && title.right <= panel.right)
            assertEquals(panel.right, details.left, 1f)
            assertEquals(root.top, details.top, 1f)
            assertEquals(root.right, details.right, 1f)
            assertEquals(root.bottom, details.bottom, 1f)
            val image = panelNode.captureToImage().toPixelMap()
            for (y in listOf(1, image.height / 2, image.height - 2)) {
                assertEquals(panelColor.toArgb(), image[1, y].toArgb())
                val divider = image[image.width - 1, y]
                // Rasterized alpha blending may round each 8-bit channel by one step.
                assertEquals(dividerColor.red, divider.red, 1.01f / 255f)
                assertEquals(dividerColor.green, divider.green, 1.01f / 255f)
                assertEquals(dividerColor.blue, divider.blue, 1.01f / 255f)
            }
            assertEquals(panelColor.toArgb(), headerNode.captureToImage().toPixelMap()[1, 1].toArgb())
            composeRule.onNodeWithTag("about-details")
                .performScrollToNode(hasText(appString(R.string.text_server_status_111)))
            assertEquals(header, headerNode.fetchSemanticsNode().boundsInRoot)
            composeRule.onNodeWithText("TiniTalk").assertIsDisplayed()
            composeRule.onNodeWithContentDescription(appString(R.string.text_back_101)).performClick()
            assertTrue(back)
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test
    fun narrowCardsPlaceCommitBelowVersion() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        composeRule.runOnUiThread {
            activity.get().setContent {
                TiniTalkTheme(darkTheme = true) {
                    AboutScreen(
                        serverUrl = "https://talk.example.com",
                        onCheckServer = {
                            ServerCheckDetails(
                                result = ServerCheckResult.Available,
                                apiVersion = 2,
                                commit = "12345678",
                            )
                        },
                        onBack = {},
                    )
                }
            }
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(hasText(appString(R.string.text_commit_105), substring = true))
                .fetchSemanticsNodes().size == 2
        }

        val versions = composeRule.onAllNodes(hasText(appString(R.string.text_version_104), substring = true))
            .fetchSemanticsNodes().sortedBy { it.boundsInRoot.top }
        val commits = composeRule.onAllNodes(hasText(appString(R.string.text_commit_105), substring = true))
            .fetchSemanticsNodes().sortedBy { it.boundsInRoot.top }

        assertEquals(2, versions.size)
        assertEquals(2, commits.size)
        versions.zip(commits).forEach { (version, commit) ->
            assertTrue(
                "Expected commit below version, version=${version.boundsInRoot}, commit=${commit.boundsInRoot}",
                commit.boundsInRoot.top > version.boundsInRoot.top,
            )
        }
        activity.pause().stop().destroy()
    }

    @Test
    fun offlineScreenDoesNotProbeServer() {
        var checks = 0
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        composeRule.runOnUiThread {
            activity.get().setContent {
                TiniTalkTheme(darkTheme = true) {
                    AboutScreen(
                        serverUrl = "https://talk.example.com",
                        internetAvailable = false,
                        onCheckServer = {
                            checks++
                            ServerCheckDetails(ServerCheckResult.Available)
                        },
                        onBack = {},
                    )
                }
            }
        }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(hasText(appString(R.string.text_no_internet_connection_3)))
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(0, checks)
        activity.pause().stop().destroy()
    }
}
