package org.tinitalk.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.tinitalk.R
import org.tinitalk.data.ContactPhotoDraft
import org.tinitalk.i18n.appString
import org.tinitalk.ui.theme.TiniTalkTheme
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ru-w800dp-h320dp-land-mdpi")
class ContactPhotoLayoutTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun landscapeEditorKeepsSquareBetweenTitleAndCompactActions() = checkCropLayout("landscape")

    @Test
    @Config(qualifiers = "ru-w640dp-h240dp-land-mdpi")
    fun shortLandscapeEditorStillFits() = checkCropLayout("short-landscape")

    @Test
    @Config(qualifiers = "ru-w360dp-h800dp-port-mdpi")
    fun portraitEditorStillFits() = checkCropLayout("portrait")

    @Test fun landscapePhotoMenuOpensFullyAndKeepsAllActionsVisible() {
        var removed = false
        withContent({
            ContactPhotoActionSheet(true, false, {}, {}, { removed = true }, {})
        }) {
            val gallery = compose.onNodeWithText(appString(R.string.text_choose_from_gallery_215)).assertIsDisplayed()
            val remove = compose.onNodeWithText(appString(R.string.text_remove_photo_217)).assertIsDisplayed()
            val galleryBounds = gallery.fetchSemanticsNode().boundsInRoot
            val removeBounds = remove.fetchSemanticsNode().boundsInRoot
            val sheet = compose.onNodeWithTag("contact-photo-actions-sheet").fetchSemanticsNode().boundsInRoot
            assertEquals(400f, sheet.width, 1f)
            assertTrue("Menu must open fully, not stop halfway down the screen", removeBounds.bottom < 320f)
            assertEquals(galleryBounds.center.x, removeBounds.center.x, 1f)
            remove.performClick()
            assertTrue(removed)
        }
    }

    @Test
    @Config(qualifiers = "ru-w640dp-h240dp-land-mdpi")
    fun shortPhotoMenuCanScrollToItsLastAction() {
        var removed = false
        withContent({
            ContactPhotoActionSheet(true, false, {}, {}, { removed = true }, {})
        }) {
            compose.onNodeWithText(appString(R.string.text_remove_photo_217))
                .performScrollTo().assertIsDisplayed().performClick()
            assertTrue(removed)
        }
    }

    private fun checkCropLayout(name: String) {
        val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.rgb(68, 102, 136))
        }
        val draft = ContactPhotoDraft("layout-test", File("unused-photo-draft"), bitmap)
        try {
            withContent({
                // A standalone overlay must not inherit a black foreground from its parent.
                CompositionLocalProvider(LocalContentColor provides Color.Black) {
                    ContactPhotoCropOverlay(
                        ContactPhotoEditorState(phase = ContactPhotoEditorPhase.Cropping, draft = draft),
                        onCancel = {}, onDone = {},
                    )
                }
            }) {
                val title = compose.onNodeWithText(appString(R.string.text_adjust_photo_218)).assertIsDisplayed()
                val titleBounds = title.fetchSemanticsNode().boundsInRoot
                val crop = compose.onNodeWithTag("contact-photo-crop-viewport").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                val actions = compose.onNodeWithTag("contact-photo-crop-actions").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
                assertEquals("Crop must remain square", crop.width, crop.height, 1f)
                assertTrue("Crop has to fit below title: $crop / $titleBounds", crop.top >= titleBounds.bottom + 11f)
                assertTrue("Crop must clear buttons: $crop / $actions", crop.bottom <= actions.top - 11f)
                assertTrue(actions.bottom <= root.bottom)
                assertTrue(actions.width <= 401f)
                assertEquals(root.center.x, actions.center.x, 1f)
                val layouts = mutableListOf<TextLayoutResult>()
                title.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertEquals(Color(0xFFF1F5F9), layouts.single().layoutInput.style.color)
                val directory = File("build/outputs/photo-layout").apply { mkdirs() }
                File(directory, "$name.png").outputStream().use {
                    compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun withContent(content: @Composable () -> Unit, check: () -> Unit) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            compose.runOnUiThread { activity.get().setContent { TiniTalkTheme(darkTheme = true, content = content) } }
            check()
        } finally {
            activity.pause().stop().destroy()
        }
    }
}
