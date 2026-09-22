package org.tinitalk.ui

import org.tinitalk.R
import org.tinitalk.i18n.appString

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import org.tinitalk.data.AccountId
import org.tinitalk.data.Contact
import org.tinitalk.data.CallHistoryItem
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
import org.junit.Assert.assertTrue
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
    @Config(qualifiers = "ru-w360dp-h800dp")
    fun scrollingPinsIdentityAndReturnButtonRestoresTheProfile() {
        render {
            ContactScreen(
                contact = contact,
                contactAddress = address,
                photoTarget = target,
                photoState = ContactPhotoEditorState(target = target),
                nameUpdate = ContactNameUpdateState(),
                history = ContactHistoryState(
                    loaded = true,
                    items = List(30) { index ->
                        CallHistoryItem(
                            id = (index + 1).toLong(), peerLogin = "alex", peerName = "Алексей",
                            direction = "incoming", outcome = "completed", reached = true,
                            startedAt = 1_788_400_000L - index * 60, durationSeconds = 30,
                        )
                    },
                ),
                ongoingCall = null,
                onBack = {}, onCall = {}, onOpenCall = {}, onRename = {},
                onRenameHandled = {}, onLoadMoreHistory = {}, onRetryHistory = {},
            )
        }
        val expanded = composeRule.onNodeWithTag("contact-profile-avatar").fetchSemanticsNode().boundsInRoot
        saveHeaderPreview("expanded")
        composeRule.onNodeWithContentDescription(appString(R.string.text_back_to_top_204)).assertDoesNotExist()
        composeRule.onNode(hasScrollAction()).performTouchInput {
            swipeUp(startY = centerY, endY = centerY - 150f, durationMillis = 1_000)
        }
        saveHeaderPreview("snapped")
        composeRule.onNodeWithContentDescription(appString(R.string.text_back_to_top_204)).assertIsDisplayed()
        val snapped = composeRule.onNodeWithTag("contact-profile-avatar").fetchSemanticsNode().boundsInRoot
        assertTrue("A short drag should fully collapse the header", snapped.width < expanded.width / 2)
        composeRule.onNode(hasScrollAction()).performTouchInput {
            swipeDown(startY = centerY, endY = centerY + 80f, durationMillis = 1_000)
        }
        composeRule.onNodeWithContentDescription(appString(R.string.text_back_to_top_204)).assertDoesNotExist()
        val reopened = composeRule.onNodeWithTag("contact-profile-avatar").fetchSemanticsNode().boundsInRoot
        assertEquals("A reverse drag should fully expand the header", expanded.width, reopened.width, 1f)
        composeRule.onNode(hasScrollAction()).performScrollToIndex(10)

        saveHeaderPreview("collapsed")
        composeRule.onNodeWithTag("contact-profile-avatar").assertIsDisplayed()
        val collapsed = composeRule.onNodeWithTag("contact-profile-avatar").fetchSemanticsNode().boundsInRoot
        assertTrue("Avatar should fit in the toolbar: $expanded -> $collapsed", collapsed.width < expanded.width / 2)
        assertTrue(collapsed.top < expanded.top)
        val day = historyDayLabel(1_788_400_000L)
        composeRule.onNodeWithText(day).assertIsDisplayed()
        val dayBounds = composeRule.onNodeWithText(day).fetchSemanticsNode().boundsInRoot
        val listBounds = composeRule.onNode(hasScrollAction()).fetchSemanticsNode().boundsInRoot
        assertEquals(listBounds.center.x, dayBounds.center.x, 1f)
        composeRule.onNode(hasScrollAction()).performScrollToIndex(15)
        assertEquals(dayBounds.top, composeRule.onNodeWithText(day).fetchSemanticsNode().boundsInRoot.top, 1f)
        composeRule.onNodeWithText("Алексей").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(appString(R.string.text_back_to_top_204)).assertIsDisplayed()

        // The photo sheet must still work after the large profile item leaves the list.
        composeRule.onNodeWithContentDescription(appString(R.string.text_actions_for_value_227, "Алексей")).performClick()
        composeRule.onNodeWithText(appString(R.string.text_change_photo_229)).performClick()
        composeRule.onNodeWithText(appString(R.string.text_choose_from_gallery_215)).assertIsDisplayed().performClick()

        composeRule.onNodeWithContentDescription(appString(R.string.text_back_to_top_204)).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(appString(R.string.text_back_to_top_204)).assertDoesNotExist()
        val restored = composeRule.onNodeWithTag("contact-profile-avatar").fetchSemanticsNode().boundsInRoot
        assertEquals(expanded.top, restored.top, 1f)
        assertEquals(expanded.width, restored.width, 1f)
        composeRule.onNodeWithText(appString(R.string.text_call_208)).assertIsDisplayed()
        composeRule.onNode(hasScrollAction()).performTouchInput {
            swipeUp(startY = centerY, endY = centerY - 150f, durationMillis = 80)
        }
        composeRule.onNodeWithText(appString(R.string.text_call_208)).assertIsNotDisplayed()
    }

    private fun saveHeaderPreview(name: String) {
        val image = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val directory = File("build/outputs/contact-header").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    @Config(qualifiers = "ru-w320dp-h720dp")
    fun longNameWithLargeTextStaysBetweenBackAndMenuWhenCollapsed() {
        val name = "Александра Константинопольская"
        var backPressed = false
        render {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                ContactScreen(
                    contact = contact.copy(displayName = name),
                    contactAddress = address,
                    nameUpdate = ContactNameUpdateState(),
                    history = ContactHistoryState(
                        loaded = true,
                        items = List(12) { index ->
                            CallHistoryItem(
                                id = (index + 1).toLong(), peerLogin = "alex", peerName = name,
                                direction = "incoming", outcome = "completed", reached = true,
                                startedAt = 1_788_400_000L - index * 60, durationSeconds = 30,
                            )
                        },
                    ),
                    ongoingCall = null,
                    onBack = { backPressed = true }, onCall = {}, onOpenCall = {}, onRename = {},
                    onRenameHandled = {}, onLoadMoreHistory = {}, onRetryHistory = {},
                )
            }
        }
        saveHeaderPreview("large-text-expanded")
        composeRule.onNode(hasScrollAction()).performScrollToIndex(6)
        saveHeaderPreview("large-text-collapsed")
        val avatar = composeRule.onNodeWithTag("contact-profile-avatar").fetchSemanticsNode().boundsInRoot
        val label = composeRule.onNodeWithText(name).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val menu = composeRule.onNodeWithContentDescription(appString(R.string.text_actions_for_value_227, name)).fetchSemanticsNode().boundsInRoot
        assertTrue("Name must clear the avatar", label.left >= avatar.right)
        assertTrue("Name must clear the menu", label.right <= menu.left)
        composeRule.onNodeWithContentDescription(appString(R.string.text_actions_for_value_227, name)).performClick()
        composeRule.onNodeWithText(appString(R.string.text_rename_228)).assertIsDisplayed().performClick()
        composeRule.onNodeWithText(appString(R.string.text_cancel_12)).performClick()
        composeRule.onNodeWithContentDescription(appString(R.string.text_back_101)).performClick()
        assertTrue(backPressed)
    }

    @Test
    fun shortcutMenuUpdatesFromSystemStateAndStillAllowsExplicitRepeatAction() {
        val pinned = mutableStateOf<Boolean?>(null)
        var pinRequests = 0
        var refreshRequests = 0
        render {
            ContactScreen(
                contact = contact,
                contactAddress = address,
                nameUpdate = ContactNameUpdateState(),
                history = ContactHistoryState(),
                ongoingCall = null,
                onBack = {}, onCall = {}, onOpenCall = {}, onRename = {},
                onRenameHandled = {}, onLoadMoreHistory = {}, onRetryHistory = {},
                shortcutPinned = pinned.value,
                onPinContact = { pinRequests++ },
                onRefreshShortcuts = { refreshRequests++ },
            )
        }

        composeRule.onNode(hasContentDescription(appString(R.string.text_actions_for_value_227, "Алексей"))).performClick()
        composeRule.onNodeWithText(appString(R.string.text_add_to_home_screen_231)).assertExists()
        assertEquals(2, refreshRequests)
        composeRule.runOnIdle { pinned.value = true }
        composeRule.onNodeWithText(appString(R.string.text_already_on_home_screen_230)).performClick()
        assertEquals(1, pinRequests)

        composeRule.runOnIdle { pinned.value = false }
        composeRule.onNode(hasContentDescription("Действия контакта Алексей")).performClick()
        composeRule.onNodeWithText(appString(R.string.text_add_to_home_screen_231)).assertExists()
        composeRule.onNodeWithText(appString(R.string.text_already_on_home_screen_230)).assertDoesNotExist()
        assertEquals(3, refreshRequests)
    }

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

        composeRule.onNode(hasContentDescription(appString(R.string.text_actions_for_value_227, "Алексей"))).assertExists().performClick()
        composeRule.onNodeWithText(appString(R.string.text_rename_228)).assertExists()
        composeRule.onNodeWithTag("contact-menu-rename").assertHeightIsAtLeast(58.dp).assertWidthIsAtLeast(260.dp)
        composeRule.onNodeWithTag("contact-menu-photo").assertHeightIsAtLeast(58.dp).assertWidthIsAtLeast(260.dp)
        composeRule.onNodeWithTag("contact-profile-avatar").assertWidthIsAtLeast(208.dp).assertHeightIsAtLeast(208.dp)
        composeRule.onNodeWithText(appString(R.string.text_change_photo_229)).assertExists().performClick()
        composeRule.onNodeWithText(appString(R.string.text_choose_from_gallery_215)).assertExists().performClick()
        assertEquals(ContactPhotoSource.Gallery, selectedSource)

        composeRule.onNode(hasContentDescription("Действия контакта Алексей")).performClick()
        composeRule.onNodeWithText(appString(R.string.text_change_photo_229)).assertExists().performClick()
        composeRule.onNodeWithText(appString(R.string.text_remove_photo_217)).assertExists().performClick()
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
        composeRule.onNodeWithText(appString(R.string.text_change_photo_229)).assertExists().performClick()
        composeRule.onNodeWithText(appString(R.string.text_remove_photo_217)).assertDoesNotExist()
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
        composeRule.onNode(hasContentDescription(appString(R.string.text_contact_name_value_203, "Алексей"))).assertExists()
        composeRule.onNode(hasContentDescription("Действия контакта Алексей")).performClick()
        composeRule.onNodeWithText(appString(R.string.text_rename_228)).assertExists().performClick()

        composeRule.onNodeWithText(appString(R.string.text_edit_name_242)).assertExists()
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

        composeRule.onNodeWithText(appString(R.string.text_cancel_12)).assertExists().performClick()
        assertEquals(true, cancelled)
        composeRule.onNodeWithText(appString(R.string.text_done_219)).assertExists().performClick()
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
