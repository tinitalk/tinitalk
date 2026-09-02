package org.tinitalk.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import org.tinitalk.data.AccountId
import org.tinitalk.data.Contact
import org.tinitalk.data.ContactAddress
import org.tinitalk.data.ContactPhotoDraft
import org.tinitalk.data.ContactPhotoReader
import org.tinitalk.ui.theme.TiniTalkTheme
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
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

        composeRule.onNode(hasContentDescription("Действия контакта Алексей")).assertExists().performClick()
        composeRule.onNodeWithText("Переименовать").assertExists()
        composeRule.onNodeWithTag("contact-menu-rename").assertHeightIsAtLeast(58.dp).assertWidthIsAtLeast(260.dp)
        composeRule.onNodeWithTag("contact-menu-photo").assertHeightIsAtLeast(58.dp).assertWidthIsAtLeast(260.dp)
        composeRule.onNodeWithTag("contact-profile-avatar").assertWidthIsAtLeast(208.dp).assertHeightIsAtLeast(208.dp)
        composeRule.onNodeWithText("Изменить фото").assertExists().performClick()
        composeRule.onNodeWithText("Выбрать из галереи").assertExists().performClick()
        assertEquals(ContactPhotoSource.Gallery, selectedSource)

        composeRule.onNode(hasContentDescription("Действия контакта Алексей")).performClick()
        composeRule.onNodeWithText("Изменить фото").assertExists().performClick()
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

        composeRule.onNode(hasContentDescription("Действия контакта Алексей")).performClick()
        composeRule.onNodeWithText("Изменить фото").assertExists().performClick()
        composeRule.onNodeWithText("Удалить фото").assertDoesNotExist()
    }

    @Test
    fun profileShowsOldPhotoWithProgressUntilReplacementFinishesLoading() {
        val reader = BlockingRefreshPhotoReader()
        render(reader) {
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
            )
        }
        composeRule.onNodeWithTag("contact-avatar-photo").assertExists()

        try {
            reader.beginRefresh()
            composeRule.waitUntil(timeoutMillis = 5_000) { reader.refreshStarted.count == 0L }
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithTag("contact-avatar-refresh-overlay")
                    .fetchSemanticsNodes().isNotEmpty()
            }

            composeRule.onNodeWithTag("contact-avatar-photo").assertExists()
            composeRule.onNodeWithTag("contact-avatar-refresh-overlay")
                .assertWidthIsEqualTo(208.dp)
                .assertHeightIsEqualTo(208.dp)
            composeRule.onNodeWithTag("contact-avatar-refresh-progress")
                .assertWidthIsEqualTo(44.dp)
                .assertHeightIsEqualTo(44.dp)
        } finally {
            reader.allowRefreshToFinish.countDown()
        }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("contact-avatar-refresh-overlay")
                .fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithTag("contact-avatar-photo").assertExists()
        composeRule.onNodeWithTag("contact-avatar-refresh-progress").assertDoesNotExist()
        val photo = composeRule.onNodeWithTag("contact-avatar-photo").captureToImage()
        assertEquals(
            android.graphics.Color.BLUE,
            photo.toPixelMap()[photo.width / 2, photo.height / 2].toArgb(),
        )
    }

    @Test
    fun contactActionsMenuOwnsRenameAndInlinePencilIsGone() {
        var renameHandled = 0
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
                onRenameHandled = { renameHandled++ },
                onLoadMoreHistory = {},
                onRetryHistory = {},
            )
        }

        composeRule.onNode(hasContentDescription("Имя контакта: Алексей. Нажмите, чтобы изменить")).assertDoesNotExist()
        composeRule.onNode(hasContentDescription("Имя контакта: Алексей")).assertExists()
        composeRule.onNode(hasContentDescription("Действия контакта Алексей")).performClick()
        composeRule.onNodeWithText("Переименовать").assertExists().performClick()

        composeRule.onNodeWithText("Изменить имя").assertExists()
        assertEquals(1, renameHandled)
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

    private fun render(
        reader: ContactPhotoReader = NoPhotoReader,
        content: @androidx.compose.runtime.Composable () -> Unit,
    ) {
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

    private object NoPhotoReader : ContactPhotoReader {
        override val revision: StateFlow<Long> = MutableStateFlow(0L)
        override fun peekBitmap(address: ContactAddress, targetPixels: Int): Bitmap? = null
        override fun loadBitmap(address: ContactAddress, targetPixels: Int): Bitmap? = null
    }

    private class BlockingRefreshPhotoReader : ContactPhotoReader {
        private val oldPhoto = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.RED)
        }
        private val newPhoto = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.BLUE)
        }
        private val revisionFlow = MutableStateFlow(0L)

        @Volatile
        private var refreshRequested = false

        val refreshStarted = CountDownLatch(1)
        val allowRefreshToFinish = CountDownLatch(1)

        override val revision: StateFlow<Long> = revisionFlow

        fun beginRefresh() {
            refreshRequested = true
            revisionFlow.value++
        }

        override fun peekBitmap(address: ContactAddress, targetPixels: Int): Bitmap? =
            oldPhoto.takeUnless { refreshRequested }

        override fun loadBitmap(address: ContactAddress, targetPixels: Int): Bitmap {
            if (!refreshRequested) return oldPhoto
            refreshStarted.countDown()
            check(allowRefreshToFinish.await(5, TimeUnit.SECONDS)) { "refresh was not released" }
            return newPhoto
        }
    }
}
