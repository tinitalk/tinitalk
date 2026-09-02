package org.tinitalk.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.tinitalk.data.AccountId
import org.tinitalk.data.ContactAddress
import org.tinitalk.data.ContactPhotoDraft
import org.tinitalk.data.ContactPhotoFailure
import org.tinitalk.data.ContactPhotoResult
import org.tinitalk.data.ContactPhotoWriteToken
import org.tinitalk.data.NormalizedCropSquare
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ContactPhotoEditorViewModelTest {
    private val address = ContactAddress.of("https://example.com", "alex")
    private val target = ContactPhotoEditTarget(AccountId("account-1"), address, "Алексей")
    private val crop = NormalizedCropSquare(0f, 0f, 1f)

    @Test
    fun pickerCancelReturnsIdleAndDoesNotChangePhoto() {
        val fake = FakeDeps(hasPhoto = true)
        val viewModel = editor(fake)

        assertTrue(viewModel.beginPicking(target, ContactPhotoSource.Gallery))
        viewModel.onPickerResult(null)

        assertEquals(ContactPhotoEditorPhase.Idle, viewModel.state.phase)
        assertTrue(viewModel.state.hasPhoto)
        assertEquals(0, fake.importCalls)
        assertEquals(0, fake.replaceCalls)
    }

    @Test
    fun latePickerResultAfterTargetHiddenIsIgnored() {
        val fake = FakeDeps()
        val viewModel = editor(fake)

        assertTrue(viewModel.beginPicking(target, ContactPhotoSource.Files))
        viewModel.onTargetHidden(target)
        viewModel.onPickerResult(Uri.parse("content://late"))

        assertEquals(ContactPhotoEditorPhase.Idle, viewModel.state.phase)
        assertEquals(0, fake.importCalls)
        assertEquals(0, fake.discardCalls)
    }

    @Test
    fun repeatedTapDuringPreparingDoesNotStartSecondOperation() {
        val fake = FakeDeps()
        val viewModel = ContactPhotoEditorViewModel(
            worker = ContactPhotoWorker { },
            mainPoster = ContactPhotoMainPoster { it() },
        )
        viewModel.configureForTest(fake.dependencies())

        assertTrue(viewModel.beginPicking(target, ContactPhotoSource.Gallery))
        viewModel.onPickerResult(Uri.parse("content://image"))

        assertEquals(ContactPhotoEditorPhase.Preparing, viewModel.state.phase)
        assertFalse(viewModel.beginPicking(target, ContactPhotoSource.Files))
        assertEquals(1, fake.beginCalls)
    }

    @Test
    fun saveUsesTokenCreatedBeforePickerAndPublishesPhoto() {
        val fake = FakeDeps(hasPhoto = false)
        val viewModel = editor(fake)

        assertTrue(viewModel.beginPicking(target, ContactPhotoSource.Gallery))
        val tokenBeforePicker = fake.lastToken
        viewModel.onPickerResult(Uri.parse("content://image"))
        assertEquals(ContactPhotoEditorPhase.Cropping, viewModel.state.phase)
        assertTrue(viewModel.save(crop))

        assertEquals(tokenBeforePicker, fake.replacedToken)
        assertEquals(ContactPhotoEditorPhase.Idle, viewModel.state.phase)
        assertTrue(viewModel.state.hasPhoto)
        assertEquals(1, fake.discardCalls)
    }

    @Test
    fun targetValidatorRunsBeforeStoreMutation() {
        val fake = FakeDeps(hasPhoto = false)
        val viewModel = editor(fake)

        assertTrue(viewModel.beginPicking(target, ContactPhotoSource.Gallery))
        viewModel.onPickerResult(Uri.parse("content://image"))
        fake.current = false
        assertTrue(viewModel.save(crop))

        assertEquals(0, fake.replaceCalls)
        assertEquals(ContactPhotoEditorPhase.Idle, viewModel.state.phase)
    }

    @Test
    fun failureReasonsMapToExactRussianMessages() {
        assertEquals("Не удалось открыть изображение", contactPhotoMessage(ContactPhotoFailure.CannotOpen))
        assertEquals("Изображение слишком большое", contactPhotoMessage(ContactPhotoFailure.TooLarge))
        assertEquals("Недостаточно места для сохранения фото", contactPhotoMessage(ContactPhotoFailure.NoSpace))
        assertEquals("Не удалось сохранить фото", contactPhotoMessage(ContactPhotoFailure.CannotSave))
    }

    @Test
    fun cropMathKeepsSquareInsideNormalizedBoundsForLandscapeAndPortrait() {
        listOf(
            normalizedCropForViewport(1600, 900, CropTransform(1.7f, 180f, -180f)),
            normalizedCropForViewport(900, 1600, CropTransform(3.6f, -400f, 400f)),
        ).forEach { square ->
            assertTrue(square.left >= 0f)
            assertTrue(square.top >= 0f)
            assertTrue(square.size > 0f)
            assertTrue(square.left + square.size <= 1f)
            assertTrue(square.top + square.size <= 1f)
        }
    }

    @Test
    fun cropMathStartsWithCoverScaleSoNonSquareImagesFillCropArea() {
        val landscape = normalizedCropForViewport(
            imageWidth = 1600,
            imageHeight = 900,
            transform = defaultCropTransform(Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888)),
        )
        val portrait = normalizedCropForViewport(
            imageWidth = 900,
            imageHeight = 1600,
            transform = defaultCropTransform(Bitmap.createBitmap(900, 1600, Bitmap.Config.ARGB_8888)),
        )

        assertEquals(900f / 1600f, landscape.size, 0.001f)
        assertEquals(900f / 1600f, portrait.size, 0.001f)
        assertEquals(0f, landscape.top, 0.001f)
        assertEquals(0f, portrait.left, 0.001f)
    }

    @Test
    fun cropMathAllowsZoomedLandscapeToPanAllTheWayToTopEdge() {
        val viewport = IntSize(280, 280)
        val topCrop = normalizedCropForViewport(
            imageWidth = 1600,
            imageHeight = 900,
            transform = clampCropTransform(
                imageWidth = 1600,
                imageHeight = 900,
                transform = CropTransform(scale = 3f, offsetX = 0f, offsetY = 10_000f),
                viewport = viewport,
            ),
            viewport = viewport,
        )

        assertEquals(0f, topCrop.top, 0.001f)
        assertTrue(topCrop.size < 900f / 1600f)
    }

    @Test
    fun cropPanClampUsesMeasuredViewportPixelsNotDpFallback() {
        val mdpi = clampCropTransform(
            imageWidth = 1600,
            imageHeight = 900,
            transform = CropTransform(scale = 3f, offsetX = 10_000f, offsetY = 10_000f),
            viewport = IntSize(280, 280),
        )
        val xxhdpi = clampCropTransform(
            imageWidth = 1600,
            imageHeight = 900,
            transform = CropTransform(scale = 3f, offsetX = 10_000f, offsetY = 10_000f),
            viewport = IntSize(840, 840),
        )

        assertEquals(mdpi.offsetX * 3f, xxhdpi.offsetX, 0.001f)
        assertEquals(mdpi.offsetY * 3f, xxhdpi.offsetY, 0.001f)
    }

    @Test
    fun cropMathAllowsStrongZoomEvenWhenSavedAvatarWillUpscale() {
        val clamped = clampCropTransform(
            imageWidth = 1600,
            imageHeight = 900,
            transform = CropTransform(scale = 12f, offsetX = 0f, offsetY = 0f),
        )
        val crop = normalizedCropForViewport(1600, 900, clamped)

        assertEquals(12f, clamped.scale, 0.001f)
        assertTrue(crop.size < 0.1f)
    }

    @Test
    fun pinchZoomKeepsImagePointUnderGestureCentroidStable() {
        val viewport = IntSize(840, 840)
        val centroid = Offset(630f, 420f)
        val before = CropTransform(scale = 3f, offsetX = 0f, offsetY = 0f)
        val beforeRect = cropRectForViewport(1600, 900, before, viewport)

        val after = applyCropGesture(
            imageWidth = 1600,
            imageHeight = 900,
            transform = before,
            centroid = centroid,
            pan = Offset.Zero,
            zoom = 1.5f,
            viewport = viewport,
        )
        val afterRect = cropRectForViewport(1600, 900, after, viewport)

        assertEquals(sourceX(beforeRect, centroid, viewport), sourceX(afterRect, centroid, viewport), 0.001f)
        assertEquals(sourceY(beforeRect, centroid, viewport), sourceY(afterRect, centroid, viewport), 0.001f)
    }

    private fun editor(fake: FakeDeps): ContactPhotoEditorViewModel =
        ContactPhotoEditorViewModel(
            worker = ContactPhotoWorker { it() },
            mainPoster = ContactPhotoMainPoster { it() },
        ).apply {
            configureForTest(fake.dependencies())
            onTargetVisible(target)
        }

    private fun sourceX(rect: CropSourceRect, point: Offset, viewport: IntSize): Float =
        rect.left + rect.size * (point.x / viewport.width)

    private fun sourceY(rect: CropSourceRect, point: Offset, viewport: IntSize): Float =
        rect.top + rect.size * (point.y / viewport.height)

    private class FakeDeps(var hasPhoto: Boolean = false) {
        var current = true
        var beginCalls = 0
        var importCalls = 0
        var replaceCalls = 0
        var discardCalls = 0
        var lastToken: ContactPhotoWriteToken? = null
        var replacedToken: ContactPhotoWriteToken? = null

        fun dependencies(): ContactPhotoEditorDependencies = ContactPhotoEditorDependencies(
            beginReplace = {
                beginCalls++
                ContactPhotoWriteToken(it, beginCalls.toLong()).also { token -> lastToken = token }
            },
            hasPhoto = { hasPhoto },
            importDraft = {
                importCalls++
                ContactPhotoResult.Success(draft())
            },
            render = { _, _ ->
                ContactPhotoResult.Success(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888))
            },
            replace = { token, _ ->
                replaceCalls++
                replacedToken = token
                Result.success(Unit)
            },
            remove = {
                hasPhoto = false
                Result.success(true)
            },
            discard = {
                discardCalls++
                true
            },
            isTargetCurrent = { current },
        )

        private fun draft(): ContactPhotoDraft =
            ContactPhotoDraft(
                id = "draft-$importCalls",
                sourceFile = File.createTempFile("contact-photo-test", ".img"),
                preview = Bitmap.createBitmap(12, 12, Bitmap.Config.ARGB_8888),
            )
    }
}
