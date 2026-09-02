package org.tinitalk.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.tinitalk.data.ContactAddress
import org.tinitalk.data.ContactPhotoReader
import org.tinitalk.ui.theme.TiniTalkTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ContactAvatarTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val address = ContactAddress.of("https://example.com", "alex")

    @Test
    fun initialScalesWithLargeAvatarAndKeepsTheSameVisualRatioAtLargeFontScale() {
        listOf(44f, 50f, 52f).forEach { avatarSizeDp ->
            assertEquals(20f, contactAvatarInitialFontSizeSp(avatarSizeDp, 1f), 0.01f)
        }
        assertEquals(28.12f, contactAvatarInitialFontSizeSp(74f, 1f), 0.01f)
        assertEquals(79.04f, contactAvatarInitialFontSizeSp(208f, 1f), 0.01f)
        assertEquals(85.12f, contactAvatarInitialFontSizeSp(224f, 1f), 0.01f)
        assertEquals(42.56f, contactAvatarInitialFontSizeSp(168f, 1.5f), 0.01f)
    }

    @Test
    fun fallbackActuallyUsesScaledInitialSize() {
        val layoutResults = mutableListOf<TextLayoutResult>()
        render(NoPhotoReader) {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1f)) {
                ContactAvatar(
                    address = null,
                    displayName = "A",
                    fallbackLogin = "alex",
                    size = 208.dp,
                )
            }
        }

        composeRule.onNode(hasText("A")).performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
            it(layoutResults)
        }

        assertEquals(79.04.sp, layoutResults.single().layoutInput.style.fontSize)
    }

    @Test
    fun addressNullShowsInitialFallbackImmediately() {
        render(NoPhotoReader) {
            ContactAvatar(
                address = null,
                displayName = "Алексей",
                fallbackLogin = "alex",
                size = 52.dp,
            )
        }

        composeRule.onNode(hasText("А")).assertExists()
        assertPhotoCount(0)
    }

    @Test
    fun missingBitmapShowsInitialFallbackImmediately() {
        render(NoPhotoReader) {
            ContactAvatar(
                address = address,
                displayName = "Алексей",
                fallbackLogin = "alex",
                size = 52.dp,
            )
        }

        composeRule.onNode(hasText("А")).assertExists()
        assertPhotoCount(0)
    }

    @Test
    fun photoAppearsAfterBackgroundLoadAndHidesFallback() {
        val reader = RecordingPhotoReader().apply { bitmap = solid(Color.RED) }

        render(reader) {
            ContactAvatar(
                address = address,
                displayName = "Алексей",
                fallbackLogin = "alex",
                size = 52.dp,
            )
        }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(hasText("А")).fetchSemanticsNodes().isEmpty()
        }
        assertPhotoCount(1)
    }

    @Test
    fun revisionReloadsSameAddress() {
        val reader = RecordingPhotoReader().apply { bitmap = solid(Color.RED) }
        render(reader) {
            ContactAvatar(
                address = address,
                displayName = "Алексей",
                fallbackLogin = "alex",
                size = 52.dp,
            )
        }
        composeRule.waitUntil(timeoutMillis = 5_000) { reader.loadCalls == 1 }

        reader.bitmap = solid(Color.BLUE)
        reader.revisionFlow.value = 1L

        composeRule.waitUntil(timeoutMillis = 5_000) { reader.loadCalls == 2 }
        assertPhotoCount(1)
    }

    @Test
    fun addressChangeClearsPreviousPhotoImmediately() {
        val first = address
        val second = ContactAddress.of("https://example.com", "beth")
        val reader = RecordingPhotoReader().apply { bitmap = solid(Color.RED) }

        render(reader) {
            ContactAvatar(
                address = if (reader.showSecond) second else first,
                displayName = if (reader.showSecond) "Бетти" else "Алексей",
                fallbackLogin = if (reader.showSecond) "beth" else "alex",
                size = 52.dp,
            )
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(hasText("А")).fetchSemanticsNodes().isEmpty()
        }

        reader.bitmap = null
        composeRule.runOnUiThread { reader.showSecond = true }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(hasText("Б")).fetchSemanticsNodes().isNotEmpty()
        }
        assertPhotoCount(0)
    }

    private fun render(reader: ContactPhotoReader, content: @androidx.compose.runtime.Composable () -> Unit) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        composeRule.runOnUiThread {
            activity.get().setContent {
                TiniTalkTheme(darkTheme = true) {
                    androidx.compose.runtime.CompositionLocalProvider(LocalContactPhotoReader provides reader) {
                        content()
                    }
                }
            }
        }
    }

    private fun solid(color: Int): Bitmap =
        Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    private fun assertPhotoCount(expected: Int) {
        org.junit.Assert.assertEquals(
            expected,
            composeRule.onAllNodesWithTag("contact-avatar-photo").fetchSemanticsNodes().size,
        )
    }

    private object NoPhotoReader : ContactPhotoReader {
        override val revision: StateFlow<Long> = MutableStateFlow(0L)
        override fun peekBitmap(address: ContactAddress, targetPixels: Int): Bitmap? = null
        override fun loadBitmap(address: ContactAddress, targetPixels: Int): Bitmap? = null
    }

    private class RecordingPhotoReader : ContactPhotoReader {
        val revisionFlow = MutableStateFlow(0L)
        var bitmap: Bitmap? = null
        var loadCalls = 0
        var showSecond by mutableStateOf(false)

        override val revision: StateFlow<Long> = revisionFlow
        override fun peekBitmap(address: ContactAddress, targetPixels: Int): Bitmap? = null
        override fun loadBitmap(address: ContactAddress, targetPixels: Int): Bitmap? {
            loadCalls++
            return bitmap
        }
    }
}
