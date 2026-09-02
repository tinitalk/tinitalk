package org.tinitalk.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.tinitalk.data.AccountId
import org.tinitalk.data.Contact
import org.tinitalk.data.ContactAddress
import org.tinitalk.data.ContactPhotoDraft
import org.tinitalk.data.ContactPhotoReader
import org.tinitalk.ui.theme.TiniTalkTheme
import java.io.File
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
class ContactScreenPhotoTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val address = ContactAddress.of("https://example.com", "alex")
    private val target = ContactPhotoEditTarget(AccountId("account-1"), address, "Алексей")
    private val contact = Contact(login = "alex", displayName = "Алексей")

    @Test
    fun actionSheetOffersGalleryFilesAndRemoveWithoutLocalStorageWarning() {
        var selectedSource: ContactPhotoSource? = null
        var removeCalled = false
        render {
            ContactScreen(
                contact = contact,
                contactAddress = address,
                photoTarget = target,
                photoState = ContactPhotoEditorState(target = target, hasPhoto = true),
                nameUpdate = ContactNameUpdateState(),
                history = ContactHistoryState(),
                ongoingCall = null,
                onBack = {},
                onCall = {},
                onOpenCall = {},
                onRename = {},
                onRenameHandled = {},
                onLoadMoreHistory = {},
                onRetryHistory = {},
                onChoosePhotoSource = { _, source -> selectedSource = source },
                onRemovePhoto = { removeCalled = true },
            )
        }

        composeRule.onNode(hasContentDescription("Изменить фото контакта Алексей")).assertExists().performClick()
        composeRule.onNodeWithText("Выбрать из галереи").assertExists().performClick()
        assertEquals(ContactPhotoSource.Gallery, selectedSource)

        composeRule.onNode(hasContentDescription("Изменить фото контакта Алексей")).performClick()
        composeRule.onNodeWithText("Удалить фото").assertExists().performClick()
        assertEquals(true, removeCalled)
        composeRule.onNode(hasText("Фото хранится только на этом устройстве")).assertDoesNotExist()
    }

    @Test
    fun removeActionHiddenWhenNoPhoto() {
        render {
            ContactScreen(
                contact = contact,
                contactAddress = address,
                photoTarget = target,
                photoState = ContactPhotoEditorState(target = target, hasPhoto = false),
                nameUpdate = ContactNameUpdateState(),
                history = ContactHistoryState(),
                ongoingCall = null,
                onBack = {},
                onCall = {},
                onOpenCall = {},
                onRename = {},
                onRenameHandled = {},
                onLoadMoreHistory = {},
                onRetryHistory = {},
            )
        }

        composeRule.onNode(hasContentDescription("Изменить фото контакта Алексей")).performClick()
        composeRule.onNodeWithText("Удалить фото").assertDoesNotExist()
    }

    @Test
    fun cropOverlayCancelsAndSubmitsCurrentSquare() {
        var cancelled = false
        var submitted = false
        val draft = ContactPhotoDraft(
            id = "draft",
            sourceFile = File.createTempFile("contact-photo-screen", ".img"),
            preview = Bitmap.createBitmap(24, 16, Bitmap.Config.ARGB_8888),
        )
        render {
            ContactPhotoCropOverlay(
                state = ContactPhotoEditorState(
                    phase = ContactPhotoEditorPhase.Cropping,
                    target = target,
                    draft = draft,
                ),
                onCancel = { cancelled = true },
                onDone = { square ->
                    submitted = true
                    assert(square.left >= 0f)
                    assert(square.top >= 0f)
                    assert(square.left + square.size <= 1f)
                    assert(square.top + square.size <= 1f)
                },
            )
        }

        composeRule.onNodeWithText("Отмена").assertExists().performClick()
        assertEquals(true, cancelled)
        composeRule.onNodeWithText("Готово").assertExists().performClick()
        assertEquals(true, submitted)
    }

    private fun render(content: @androidx.compose.runtime.Composable () -> Unit) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        composeRule.runOnUiThread {
            activity.get().setContent {
                TiniTalkTheme(darkTheme = true) {
                    androidx.compose.runtime.CompositionLocalProvider(LocalContactPhotoReader provides NoPhotoReader) {
                        content()
                    }
                }
            }
        }
    }

    private object NoPhotoReader : ContactPhotoReader {
        override val revision: StateFlow<Long> = MutableStateFlow(0L)
        override fun peekBitmap(address: ContactAddress, targetPixels: Int): Bitmap? = null
        override fun loadBitmap(address: ContactAddress, targetPixels: Int): Bitmap? = null
    }
}
