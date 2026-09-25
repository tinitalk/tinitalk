package org.tinitalk.ui.call

import org.tinitalk.R
import org.tinitalk.i18n.appString

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.tinitalk.call.CallEndReason
import org.tinitalk.call.CallVideoState
import org.tinitalk.call.ConnectionHealth
import org.tinitalk.call.CallTransportRoute
import org.tinitalk.call.CallSecurityFailureReason
import org.tinitalk.call.CallSecurityState
import org.tinitalk.call.CallSecurityUnavailableReason
import org.tinitalk.data.ContactAddress
import org.tinitalk.data.ContactPhotoReader
import org.tinitalk.media.VideoRenderSource
import org.tinitalk.ui.LocalContactPhotoReader
import org.tinitalk.ui.ContactScreen
import org.tinitalk.ui.ContactNameUpdateState
import org.tinitalk.ui.ContactHistoryState
import org.tinitalk.data.Contact
import org.tinitalk.ui.theme.TiniTalkTheme
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
class CallComponentsTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    @Config(qualifiers = "w640dp-h360dp-land-mdpi")
    fun landscapeVideoHeaderIsCenteredOnTheImageWithEitherSideInset() {
        val frame = mutableStateOf(720 to 1280)
        val insets = mutableStateOf(WindowInsets(left = 24, top = 24, right = 48, bottom = 0))
        val activity = render {
            Box(Modifier.fillMaxSize()) {
                val size = cameraVideoSize(640f, 360f, frame.value.first, frame.value.second)
                Box(Modifier.align(Alignment.Center).size(size.width.dp, size.height.dp).testTag("video-image"))
                Box(Modifier.align(Alignment.TopCenter).videoCallHeaderInsets(true, insets.value)) {
                    VideoCallHeader(appString(R.string.text_in_a_call_133), "Alice", "00:03", Color.White)
                }
            }
        }
        try {
            for (source in listOf(720 to 1280, 1280 to 720)) {
                for ((left, right) in listOf(24 to 48, 48 to 24)) {
                    composeRule.runOnIdle {
                        frame.value = source
                        insets.value = WindowInsets(left = left, top = 24, right = right, bottom = 0)
                    }
                    val image = composeRule.onNodeWithTag("video-image").fetchSemanticsNode().boundsInRoot
                    for (text in listOf(appString(R.string.text_in_a_call_133), "Alice", "00:03")) {
                        val bounds = composeRule.onNodeWithText(text).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                        assertEquals(image.center.x, bounds.center.x, 1f)
                        assertTrue(bounds.left >= left && bounds.right <= 640f - right - 96f)
                        assertTrue(bounds.top >= 24f)
                    }
                }
            }
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp-land-mdpi")
    fun videoHeaderKeepsPortraitStackAndTextSizesInAWiderViewport() {
        val width = mutableStateOf(360.dp)
        val activity = render {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.width(width.value).testTag("video-header-viewport"), contentAlignment = Alignment.TopCenter) {
                    VideoCallHeader(appString(R.string.text_in_a_call_133), "Alice", "00:03", Color.White)
                }
            }
        }
        try {
            fun textBounds() = listOf(appString(R.string.text_in_a_call_133), "Alice", "00:03")
                .map { composeRule.onNodeWithText(it).assertIsDisplayed().fetchSemanticsNode().boundsInRoot }
            val portrait = textBounds()
            composeRule.runOnIdle { width.value = 544.dp }
            val landscape = textBounds()
            val viewport = composeRule.onNodeWithTag("video-header-viewport").fetchSemanticsNode().boundsInRoot
            assertTrue(landscape[0].bottom + 7f <= landscape[1].top)
            assertTrue(landscape[1].bottom <= landscape[2].top)
            portrait.zip(landscape).forEach { (before, after) ->
                assertEquals(viewport.center.x, after.center.x, 1f)
                assertEquals(before.top, after.top, 1f)
                assertEquals(before.size, after.size)
            }
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp-land-mdpi")
    fun landscapeContactAndCallPhasesShareFullHeightPanel() {
        val screen = mutableStateOf(0)
        val activity = render {
            Box(Modifier.fillMaxSize().testTag("screen")) {
                when (screen.value) {
                    0 -> ContactScreen(
                        contact = Contact("alice", "Alice"),
                        contactAddress = ContactAddress.of("https://example.com", "alice"),
                        nameUpdate = ContactNameUpdateState(), history = ContactHistoryState(loaded = true),
                        ongoingCall = null, onBack = {}, onCall = {}, onOpenCall = {},
                        onRename = {}, onRenameHandled = {}, onLoadMoreHistory = {}, onRetryHistory = {},
                    )
                    1 -> IncomingCallScreen("call", "Alice", onAnswer = {}, onReject = {})
                    2 -> OutgoingCallScreen("Alice", muted = false, currentEndpoint = null,
                        availableEndpoints = emptyList(), onMute = {}, onSelectEndpoint = {}, onCancel = {})
                    3 -> ActiveCallScreen("Alice", durationText = "00:03", muted = false,
                        connectionHealth = ConnectionHealth.Good,
                        currentEndpoint = null, availableEndpoints = emptyList(),
                        videoState = CallVideoState(allowed = false),
                        onMute = {}, onSelectEndpoint = {}, onCamera = {}, onSwitchCamera = {},
                        onVideoVisibilityChanged = {}, onEnd = {})
                    else -> EndedCallScreen("Alice", CallEndReason.RemoteHangup)
                }
            }
        }
        try {
            val panel = composeRule.onNodeWithTag("landscape-identity-panel")
            val expected = panel.fetchSemanticsNode().boundsInRoot
            val root = composeRule.onNodeWithTag("screen").fetchSemanticsNode().boundsInRoot
            assertEquals(root.top, expected.top, 0.5f)
            assertEquals(root.bottom, expected.bottom, 0.5f)
            assertEquals(root.width * 0.38f, expected.width, 1f)
            val reference = panel.captureToImage().toPixelMap()
            val background = reference[reference.width / 2, 1].toArgb()
            val divider = reference[reference.width - 1, 1].toArgb()
            assertTrue("Panel must have a visible divider", background != divider)
            for (index in 1..4) {
                composeRule.runOnIdle { screen.value = index }
                assertEquals("Call phase $index", expected, panel.fetchSemanticsNode().boundsInRoot)
                val image = panel.captureToImage().toPixelMap()
                assertEquals(background, image[image.width / 2, 1].toArgb())
                assertEquals(divider, image[image.width - 1, 1].toArgb())
                composeRule.onNodeWithText("Alice").assertIsDisplayed()
            }
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test
    @Config(qualifiers = "w640dp-h384dp-land-mdpi")
    fun landscapeCallPinsDetailsToBottomAndCapsPhotoInRemainingSpace() {
        val route = mutableStateOf(CallTransportRoute.Direct)
        val activity = render {
            ActiveCallScreen("Alice", durationText = "00:03", muted = false,
                connectionHealth = ConnectionHealth.Good,
                transportRoute = route.value,
                security = CallSecurityState.Ready("4821 7034 1596"),
                currentEndpoint = null, availableEndpoints = emptyList(),
                videoState = CallVideoState(allowed = false),
                onMute = {}, onSelectEndpoint = {}, onCamera = {}, onSwitchCamera = {},
                onVideoVisibilityChanged = {}, onEnd = {})
        }
        try {
            val panel = composeRule.onNodeWithTag("landscape-identity-panel").fetchSemanticsNode().boundsInRoot
            val avatar = composeRule.onNodeWithTag("call-peer-avatar").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val name = composeRule.onNodeWithText("Alice").fetchSemanticsNode().boundsInRoot
            val duration = composeRule.onNodeWithText("00:03").fetchSemanticsNode().boundsInRoot
            val security = composeRule.onNodeWithTag("security_code_panel").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val indicator = composeRule.onNodeWithTag("call-transport-route").fetchSemanticsNode().boundsInRoot
            assertEquals(panel.bottom - 8f, security.bottom, 1f)
            assertTrue(name.bottom < duration.top)
            assertTrue(duration.bottom <= security.top)
            val status = composeRule.onNodeWithText(appString(R.string.text_in_a_call_133)).fetchSemanticsNode().boundsInRoot
            val info = composeRule.onNodeWithTag("landscape-call-info").fetchSemanticsNode().boundsInRoot
            val controls = composeRule.onNodeWithTag("landscape-call-grid").fetchSemanticsNode().boundsInRoot
            assertTrue(status.left >= panel.right && indicator.left >= panel.right)
            assertEquals(info.center.x, status.center.x, 1f)
            assertEquals(info.center.x, indicator.center.x, 1f)
            assertTrue(status.bottom <= indicator.top && indicator.bottom < controls.top)
            assertTrue(name.top - avatar.bottom >= 8f)
            assertEquals(180f, avatar.height, 1f)
            assertEquals(avatar.width, avatar.height, 1f)
            assertEquals(panel.center.x, avatar.center.x, 1f)
            assertTrue(name.right <= panel.right && duration.right <= panel.right && security.right <= panel.right)
            for (newRoute in listOf(CallTransportRoute.Turn, CallTransportRoute.Unknown)) {
                composeRule.runOnIdle { route.value = newRoute }
                assertEquals(avatar, composeRule.onNodeWithTag("call-peer-avatar").fetchSemanticsNode().boundsInRoot)
                assertEquals(name, composeRule.onNodeWithText("Alice").fetchSemanticsNode().boundsInRoot)
            }
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test
    @Config(qualifiers = "w640dp-h320dp-land-mdpi")
    fun landscapeCallFitsLongNameAndLargerTextWithoutOverlappingPhoto() {
        val activity = render {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.3f)) {
                ActiveCallScreen("Alexandra Montgomery", durationText = "00:03", muted = false,
                    connectionHealth = ConnectionHealth.Good, transportRoute = CallTransportRoute.Turn,
                    security = CallSecurityState.Ready("4821 7034 1596"),
                    currentEndpoint = null, availableEndpoints = emptyList(),
                    videoState = CallVideoState(allowed = false),
                    onMute = {}, onSelectEndpoint = {}, onCamera = {}, onSwitchCamera = {},
                    onVideoVisibilityChanged = {}, onEnd = {})
            }
        }
        try {
            val avatar = composeRule.onNodeWithTag("call-peer-avatar").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val name = composeRule.onNodeWithText("Alexandra Montgomery").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val indicator = composeRule.onNodeWithTag("call-transport-route").fetchSemanticsNode().boundsInRoot
            val security = composeRule.onNodeWithTag("security_code_panel").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val panel = composeRule.onNodeWithTag("landscape-identity-panel").fetchSemanticsNode().boundsInRoot
            composeRule.onNodeWithText("00:03").assertIsDisplayed()
            assertTrue(indicator.left >= panel.right)
            assertTrue(name.top - avatar.bottom >= 8f)
            assertTrue("Photo must shrink when space is limited", avatar.height < 180f)
            assertEquals(avatar.width, avatar.height, 1f)
            assertEquals(panel.bottom - 8f, security.bottom, 1f)
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test
    @Config(qualifiers = "w640dp-h320dp-land")
    fun landscapeUsesGridForAudioAndFullHeightRightRailForVideoAndScreenViewing() {
        val video = mutableStateOf(CallVideoState<VideoRenderSource>(allowed = true))
        var muted = false
        var ended = false
        val activity = render {
            ActiveCallScreen(peerName = "Alice", durationText = "00:03", muted = false,
                connectionHealth = ConnectionHealth.Good,
                currentEndpoint = null, availableEndpoints = emptyList(), videoState = video.value,
                onMute = { muted = it }, onSelectEndpoint = {}, onCamera = {},
                onSwitchCamera = {}, onVideoVisibilityChanged = {}, onEnd = { ended = true })
        }
        try {
            val descriptions = listOf(R.string.text_mute_microphone_180, R.string.text_end_call_98)
            fun positions() = descriptions.map { description ->
                composeRule.onNodeWithContentDescription(appString(description)).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            }
            val expected = positions()
            assertTrue(expected[0].right < expected[1].left)
            assertEquals(expected[0].center.y, expected[1].center.y, 1f)
            val avatar = composeRule.onNodeWithTag("call-peer-avatar").fetchSemanticsNode().boundsInRoot
            assertTrue(expected.all { it.left > avatar.right })
            composeRule.onNodeWithTag("landscape-call-grid").assertIsDisplayed()
            composeRule.onNodeWithTag("landscape-call-controls").assertDoesNotExist()
            for (state in listOf(
                CallVideoState<VideoRenderSource>(allowed = true, remoteSending = true),
                CallVideoState<VideoRenderSource>(allowed = true, requested = true, sending = true),
                CallVideoState<VideoRenderSource>(allowed = true,
                    screen = org.tinitalk.call.ScreenShareState(allowed = true, localId = "share")),
                CallVideoState<VideoRenderSource>(allowed = true, remoteSending = true,
                    screen = org.tinitalk.call.ScreenShareState(allowed = true, remoteId = "share", ready = true)),
                CallVideoState<VideoRenderSource>(allowed = true),
            )) {
                composeRule.runOnIdle { video.value = state }
                val actual = positions()
                val viewing = state.screen.remoteId != null || (!state.screen.active && (state.sending || state.remoteSending))
                if (viewing) {
                    composeRule.onNodeWithTag("landscape-call-grid").assertDoesNotExist()
                    val panel = composeRule.onNodeWithTag("landscape-call-controls").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                    assertEquals(panel.center.x, actual[0].center.x, 1f)
                    assertEquals(panel.center.x, actual[1].center.x, 1f)
                    assertTrue(actual[0].bottom < actual[1].top)
                    val count = if (state.screen.active) 3 else 5
                    assertEquals(panel.top + panel.height * (count - 1.5f) / count, actual[0].center.y, 1f)
                    assertEquals(panel.top + panel.height * (count - 0.5f) / count, actual[1].center.y, 1f)
                    val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
                    val background = composeRule.onNodeWithTag("landscape-call-panel-background").fetchSemanticsNode().boundsInRoot
                    assertEquals(root.right, background.right, 1f)
                    assertEquals(root.top, background.top, 1f)
                    assertEquals(root.bottom, background.bottom, 1f)
                } else {
                    composeRule.onNodeWithTag("landscape-call-grid").assertIsDisplayed()
                    composeRule.onNodeWithTag("landscape-call-controls").assertDoesNotExist()
                    expected.zip(actual).forEach { (a, b) ->
                        assertEquals(a.center.x, b.center.x, 1f)
                        assertEquals(a.center.y, b.center.y, 1f)
                    }
                }
                for (label in listOf(R.string.text_camera_175, R.string.text_rotate_173,
                    R.string.text_audio_181, R.string.text_microphone_178, R.string.text_end_call_98)) {
                    composeRule.onNodeWithText(appString(label)).assertDoesNotExist()
                }
                val cameraDescription = appString(if (state.requested) R.string.text_turn_camera_off_176 else R.string.text_turn_camera_on_177)
                if (state.screen.active) composeRule.onNodeWithContentDescription(cameraDescription).assertDoesNotExist()
                else composeRule.onNodeWithContentDescription(cameraDescription).assertIsDisplayed()
                val switchCamera = composeRule.onNodeWithContentDescription(appString(R.string.text_switch_camera_174))
                if (viewing && !state.screen.active) {
                    switchCamera.assertIsDisplayed()
                    // No camera frames in this test: just like portrait, keep the disabled action visible.
                    switchCamera.assertIsNotEnabled()
                    val switchBounds = switchCamera.fetchSemanticsNode().boundsInRoot
                    assertEquals(actual.first().center.x, switchBounds.center.x, 1f)
                    assertTrue(switchBounds.bottom < actual.first().top)
                } else switchCamera.assertDoesNotExist()
            }
            composeRule.onNodeWithContentDescription(appString(R.string.text_mute_microphone_180)).performClick()
            composeRule.onNodeWithContentDescription(appString(R.string.text_end_call_98)).performClick()
            assertTrue(muted)
            assertTrue(ended)
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test
    @Config(qualifiers = "w640dp-h440dp-land-mdpi")
    fun landscapeMediaButtonsGrowWithAvailableHeightAndFitThreeToFiveActions() {
        val configuration = mutableStateOf(440 to 3)
        val activity = render {
            val (height, count) = configuration.value
            Box(Modifier.fillMaxSize()) {
                LandscapeCallControls(muted = false, currentEndpoint = null, availableEndpoints = emptyList(),
                    cameraVisible = count >= 4, cameraEnabled = true, cameraRequested = true,
                    switchCameraVisible = count == 5, switchCameraEnabled = true,
                    onMute = {}, onSelectEndpoint = {}, onShowRoutePicker = {}, onCamera = {}, onEnd = {},
                    modifier = Modifier.align(Alignment.CenterEnd).height(height.dp))
            }
        }
        try {
            for (height in listOf(440, 320)) for (count in 3..5) {
                composeRule.runOnIdle { configuration.value = height to count }
                val panel = composeRule.onNodeWithTag("landscape-call-controls").fetchSemanticsNode().boundsInRoot
                val descriptions = buildList {
                    if (count == 5) add(appString(R.string.text_switch_camera_174))
                    if (count >= 4) add(appString(R.string.text_turn_camera_off_176))
                    add(appString(R.string.text_choose_audio_device_current_value_184, appString(R.string.text_device_191)))
                    add(appString(R.string.text_mute_microphone_180))
                    add(appString(R.string.text_end_call_98))
                }
                val buttons = descriptions.map {
                    composeRule.onNodeWithContentDescription(it).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                }
                val expectedSize = minOf(72f, panel.height / count - 8f)
                for (button in buttons) {
                    assertEquals(expectedSize, button.width, 1f)
                    assertEquals(expectedSize, button.height, 1f)
                    assertTrue(button.left >= panel.left && button.right <= panel.right)
                    assertTrue(button.top >= panel.top && button.bottom <= panel.bottom)
                }
                assertTrue(buttons.zipWithNext().all { (above, below) -> below.top - above.bottom >= 7f })
            }
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test
    @Config(qualifiers = "w640dp-h320dp-land-mdpi")
    fun outgoingAndActiveAudioShareCompactGridWithLargerButtons() {
        val active = mutableStateOf(false)
        var canceled = false
        val activity = render {
            if (active.value) ActiveCallScreen("Alice", durationText = "00:03", muted = false,
                connectionHealth = ConnectionHealth.Good,
                currentEndpoint = null, availableEndpoints = emptyList(),
                videoState = CallVideoState(allowed = true),
                onMute = {}, onSelectEndpoint = {}, onCamera = {}, onSwitchCamera = {},
                onVideoVisibilityChanged = {}, onEnd = {})
            else OutgoingCallScreen("Alice", muted = false, currentEndpoint = null,
                availableEndpoints = emptyList(), onMute = {}, onSelectEndpoint = {}, onCancel = { canceled = true })
        }
        try {
            fun positions() = listOf(
                appString(R.string.text_turn_camera_on_177),
                appString(R.string.text_choose_audio_device_current_value_184, appString(R.string.text_device_191)),
                appString(R.string.text_mute_microphone_180),
                appString(if (active.value) R.string.text_end_call_98 else R.string.call_cancel),
            ).map { composeRule.onNodeWithContentDescription(it).assertIsDisplayed().fetchSemanticsNode().boundsInRoot }
            val expected = positions()
            val grid = composeRule.onNodeWithTag("landscape-call-grid").fetchSemanticsNode().boundsInRoot
            val panel = composeRule.onNodeWithTag("landscape-identity-panel").fetchSemanticsNode().boundsInRoot
            assertEquals(200f, grid.width, 1f)
            assertEquals(200f, grid.height, 1f)
            val info = composeRule.onNodeWithTag("landscape-call-info").fetchSemanticsNode().boundsInRoot
            val status = composeRule.onNodeWithTag("landscape-call-status").fetchSemanticsNode().boundsInRoot
            assertEquals((status.bottom + info.bottom) / 2f, grid.center.y, 1f)
            assertTrue(grid.left > panel.right)
            expected.forEachIndexed { index, bounds ->
                assertEquals(88f, bounds.width, 1f)
                assertEquals(88f, bounds.height, 1f)
                assertEquals(grid.left + 44f + (index % 2) * 112f, bounds.center.x, 1f)
                assertEquals(grid.top + 44f + (index / 2) * 112f, bounds.center.y, 1f)
            }
            composeRule.onNodeWithContentDescription(appString(R.string.text_turn_camera_on_177), useUnmergedTree = true)
                .assertWidthIsEqualTo(36.dp).assertHeightIsEqualTo(36.dp)
            composeRule.onNodeWithContentDescription(appString(R.string.text_turn_camera_on_177)).assertIsNotEnabled()
            composeRule.onNodeWithContentDescription(appString(R.string.call_cancel)).performClick()
            assertTrue(canceled)
            composeRule.runOnIdle { active.value = true }
            assertEquals(expected, positions())
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp-land")
    fun outgoingActionsAndIdentityStayVisibleInLandscape() {
        val activity = render {
            OutgoingCallScreen(callee = "Alice", muted = false, currentEndpoint = null,
                availableEndpoints = emptyList(), onMute = {}, onSelectEndpoint = {}, onCancel = {})
        }
        try {
            composeRule.onNodeWithTag("call-peer-avatar", useUnmergedTree = true).assertIsDisplayed()
            composeRule.onNodeWithText("Alice").assertIsDisplayed()
            composeRule.onNodeWithContentDescription(appString(R.string.call_cancel)).assertIsDisplayed()
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp-land")
    fun incomingActionsStayVisibleInLandscape() {
        val activity = render {
            IncomingCallScreen(callId = "landscape", caller = "Alice",
                replySupported = true, onAnswer = {}, onReject = {})
        }
        try {
            composeRule.onNodeWithText("Alice").assertIsDisplayed()
            composeRule.onNodeWithContentDescription(appString(R.string.text_answer_64)).assertIsDisplayed()
            composeRule.onNodeWithContentDescription(appString(R.string.text_decline_63)).assertIsDisplayed()
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test
    fun securityCodeIsDisplayedWithoutConfirmationAction() {
        val activity = renderSecurity(CallSecurityState.Ready("4821 7034 1596"))

        composeRule.onNodeWithTag("security_code", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("🏰 🍆 🧵 🛷 🧬").assertExists()
        composeRule.onNodeWithText("Сверьте весь код голосом").assertDoesNotExist()
        composeRule.onAllNodesWithTag("security_code_confirm").assertCountEquals(0)
        composeRule.onNodeWithTag("security_code_panel").performClick()
        composeRule.onNodeWithText(appString(R.string.text_security_code_146)).assertExists()
        composeRule.onNodeWithText(
            "Сравните все 5 эмодзи с собеседником. Если они совпадают, соединение защищено. " +
                "Если отличается хотя бы один эмодзи, завершите звонок.",
        ).assertExists()
        activity.pause().stop().destroy()
    }

    @Test
    fun failedSecurityCheckShowsExactReasonAndWarningIcon() {
        val activity = renderSecurity(
            CallSecurityState.Failed(CallSecurityFailureReason.FingerprintMismatch),
        )

        composeRule.onNodeWithText(appString(R.string.text_connection_is_not_secure_142)).performClick()
        composeRule.onNodeWithTag("security_code_error_icon").assertExists()
        composeRule.onNodeWithText(
            "Сертификат соединения не совпал с данными проверки. Звонок небезопасен." +
                "\n\nЗавершите звонок и не сообщайте конфиденциальные данные.",
        ).assertExists()
        activity.pause().stop().destroy()
    }

    @Test
    fun unsupportedPeerExplainsHowToRestoreSecurityCode() {
        val activity = renderSecurity(
            CallSecurityState.Unavailable(CallSecurityUnavailableReason.PeerUnsupported),
        )

        composeRule.onNodeWithText(appString(R.string.text_cannot_verify_connection_security_140)).performClick()
        composeRule.onNodeWithText(
            "Приложение собеседника устарело. Безопасность этого звонка нельзя подтвердить.",
        ).assertExists()
        activity.pause().stop().destroy()
    }

    @Test
    fun securityCodeIsHiddenWhileVideoIsActive() {
        val activity = renderSecurity(
            security = CallSecurityState.Ready("4821 7034 1596"),
            videoState = CallVideoState(
                allowed = true,
                requested = true,
                sending = true,
            ),
        )

        composeRule.onAllNodesWithTag("security_code_panel").assertCountEquals(0)
        activity.pause().stop().destroy()
    }

    @Test
    @Config(qualifiers = "ru-w320dp-h480dp")
    fun securityStatusFitsBelowDurationOnShortAudioScreen() {
        val activity = renderSecurity(
            CallSecurityState.Unavailable(CallSecurityUnavailableReason.PeerUnsupported),
        )

        val duration = composeRule.onNodeWithText("00:03").fetchSemanticsNode().boundsInRoot
        val security = composeRule.onNodeWithTag("security_code_panel").fetchSemanticsNode().boundsInRoot
        val avatar = composeRule.onNodeWithTag("call-peer-avatar").fetchSemanticsNode().boundsInRoot
        assertTrue("security status must be below call duration", security.top >= duration.bottom)
        assertTrue("security status must not overlap the avatar", security.bottom <= avatar.top)
        activity.pause().stop().destroy()
    }

    @Test
    fun hiddenActionLabelKeepsAccessibleDescription() {
        val activity = render {
            RoundCallAction(
                label = "Звук",
                contentDescription = "Выбрать звук",
                color = Color.DarkGray,
                onClick = {},
                showLabel = false,
            )
        }

        composeRule.onNodeWithText(appString(R.string.text_audio_181)).assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Выбрать звук").assertExists()

        activity.pause().stop().destroy()
    }

    @Test
    fun muteActionKeepsActionDescriptionForBothStates() {
        val muted = mutableStateOf(false)
        val activity = render {
            MuteCallAction(muted = muted.value, onMute = { muted.value = it })
        }

        composeRule.onNodeWithContentDescription(appString(R.string.text_mute_microphone_180)).assertExists()
        composeRule.runOnUiThread { muted.value = true }
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription(appString(R.string.text_unmute_microphone_179)).assertExists()
        activity.pause().stop().destroy()
    }

    @Test
    fun callSurfaceUsesContactAvatarPhotoWhenAddressIsPresent() {
        val address = ContactAddress.of("https://calls.example", "alex")
        val reader = RecordingReader(address)

        render(reader) {
            CallScreenSurface(
                status = "Звоним…",
                peerName = "Алексей",
                contactAddress = address,
                fallbackLogin = "alex",
            ) {}
        }

        composeRule.waitUntil(timeoutMillis = 5_000) { reader.requested.isNotEmpty() }
        assertEquals(address, reader.requested.first())
        assertEquals(1, composeRule.onAllNodesWithTag("contact-avatar-photo").fetchSemanticsNodes().size)
    }

    @Test
    fun callSurfaceUsesLargePeerAvatarForRingingAndDialingScreens() {
        render {
            CallScreenSurface(
                status = "Звоним…",
                peerName = "Алексей",
                prominentAvatar = true,
            ) {}
        }

        composeRule.onNodeWithTag("call-peer-avatar")
            .assertWidthIsEqualTo(224.dp)
            .assertHeightIsEqualTo(224.dp)
    }

    @Test
    @Config(qualifiers = "ru-w411dp-h891dp")
    fun activeAudioCallUsesTheSameLargeAvatarAsIncomingCall() {
        render {
            ActiveCallScreen(
                peerName = "Алексей",
                durationText = "00:03",
                muted = false,
                connectionHealth = ConnectionHealth.Good,
                currentEndpoint = null,
                availableEndpoints = emptyList(),
                videoState = CallVideoState(allowed = false),
                onMute = {},
                onSelectEndpoint = {},
                onCamera = {},
                onSwitchCamera = {},
                onVideoVisibilityChanged = {},
                onEnd = {},
            )
        }

        composeRule.onNodeWithTag("call-peer-avatar")
            .assertWidthIsEqualTo(224.dp)
            .assertHeightIsEqualTo(224.dp)
    }

    @Test
    @Config(qualifiers = "ru-w411dp-h891dp")
    fun transportRouteChangesWithoutMovingTheAudioCallAvatar() {
        val route = mutableStateOf(CallTransportRoute.Unknown)
        render {
            ActiveCallScreen(
                peerName = "Алексей",
                durationText = "00:03",
                muted = false,
                connectionHealth = ConnectionHealth.Good,
                transportRoute = route.value,
                currentEndpoint = null,
                availableEndpoints = emptyList(),
                videoState = CallVideoState(allowed = false),
                onMute = {},
                onSelectEndpoint = {},
                onCamera = {},
                onSwitchCamera = {},
                onVideoVisibilityChanged = {},
                onEnd = {},
            )
        }

        val initialAvatarY = composeRule.onNodeWithTag("call-peer-avatar")
            .fetchSemanticsNode().boundsInRoot.center.y

        composeRule.runOnUiThread { route.value = CallTransportRoute.Direct }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(appString(R.string.text_direct_connection_167)).assertExists()
        assertEquals(
            initialAvatarY,
            composeRule.onNodeWithTag("call-peer-avatar").fetchSemanticsNode().boundsInRoot.center.y,
            0.01f,
        )

        composeRule.runOnUiThread { route.value = CallTransportRoute.Turn }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(appString(R.string.text_connection_via_turn_168)).assertExists()
        assertEquals(
            initialAvatarY,
            composeRule.onNodeWithTag("call-peer-avatar").fetchSemanticsNode().boundsInRoot.center.y,
            0.01f,
        )
    }

    @Test
    @Config(qualifiers = "ru-w411dp-h891dp")
    fun primaryCallStagesKeepTheAvatarAtTheSameVerticalPosition() {
        val stage = mutableStateOf(0)
        render {
            when (stage.value) {
                0 -> IncomingCallScreen(
                    callId = "call-1",
                    caller = "Алексей",
                    onAnswer = {},
                    onReject = {},
                )
                1 -> OutgoingCallScreen(
                    callee = "Алексей",
                    muted = false,
                    currentEndpoint = null,
                    availableEndpoints = emptyList(),
                    onMute = {},
                    onSelectEndpoint = {},
                    onCancel = {},
                )
                2 -> ActiveCallScreen(
                    peerName = "Алексей",
                    durationText = "00:03",
                    muted = false,
                    connectionHealth = ConnectionHealth.Good,
                    transportRoute = CallTransportRoute.Direct,
                    currentEndpoint = null,
                    availableEndpoints = emptyList(),
                    videoState = CallVideoState(allowed = false),
                    onMute = {},
                    onSelectEndpoint = {},
                    onCamera = {},
                    onSwitchCamera = {},
                    onVideoVisibilityChanged = {},
                    onEnd = {},
                )
                3 -> ActiveCallScreen(
                    peerName = "Алексей",
                    durationText = "00:03",
                    muted = false,
                    connectionHealth = ConnectionHealth.Good,
                    currentEndpoint = null,
                    availableEndpoints = emptyList(),
                    videoState = CallVideoState(
                        allowed = true,
                        requested = true,
                        sending = true,
                    ),
                    onMute = {},
                    onSelectEndpoint = {},
                    onCamera = {},
                    onSwitchCamera = {},
                    onVideoVisibilityChanged = {},
                    onEnd = {},
                )
                else -> EndedCallScreen("Алексей", CallEndReason.RemoteHangup)
            }
        }

        val initialAvatarY = composeRule.onNodeWithTag("call-peer-avatar")
            .fetchSemanticsNode().boundsInRoot.center.y
        for (nextStage in 1..4) {
            composeRule.runOnUiThread { stage.value = nextStage }
            composeRule.waitForIdle()
            assertEquals(
                initialAvatarY,
                composeRule.onNodeWithTag("call-peer-avatar").fetchSemanticsNode().boundsInRoot.center.y,
                0.01f,
            )
        }
    }

    @Test
    @Config(qualifiers = "ru-w411dp-h891dp")
    fun activeCallKeepsDisabledCameraSlotBeforeVideoIsAllowed() {
        render {
            ActiveCallScreen(
                peerName = "Алексей",
                durationText = "00:03",
                muted = false,
                connectionHealth = ConnectionHealth.Good,
                currentEndpoint = null,
                availableEndpoints = emptyList(),
                videoState = CallVideoState(allowed = false),
                onMute = {},
                onSelectEndpoint = {},
                onCamera = {},
                onSwitchCamera = {},
                onVideoVisibilityChanged = {},
                onEnd = {},
            )
        }

        composeRule.onNodeWithContentDescription(appString(R.string.text_turn_camera_on_177)).assertIsNotEnabled()
        composeRule.onNodeWithText(appString(R.string.text_camera_175)).assertExists()
    }

    @Test
    @Config(qualifiers = "ru-w411dp-h891dp")
    fun outgoingCallShowsDisabledCameraSlotImmediately() {
        render {
            OutgoingCallScreen(
                callee = "Алексей",
                muted = false,
                currentEndpoint = null,
                availableEndpoints = emptyList(),
                onMute = {},
                onSelectEndpoint = {},
                onCancel = {},
            )
        }

        composeRule.onNodeWithContentDescription(appString(R.string.text_turn_camera_on_177)).assertIsNotEnabled()
        composeRule.onNodeWithText(appString(R.string.text_camera_175)).assertExists()
    }

    @Test
    @Config(qualifiers = "ru-w411dp-h891dp")
    fun localOnlyVideoKeepsTheAudioCallIdentityLayout() {
        render {
            ActiveCallScreen(
                peerName = "Алексей",
                durationText = "00:03",
                muted = false,
                connectionHealth = ConnectionHealth.Good,
                currentEndpoint = null,
                availableEndpoints = emptyList(),
                videoState = CallVideoState(
                    allowed = true,
                    requested = true,
                    sending = true,
                    remoteSending = false,
                ),
                onMute = {},
                onSelectEndpoint = {},
                onCamera = {},
                onSwitchCamera = {},
                onVideoVisibilityChanged = {},
                onEnd = {},
            )
        }

        composeRule.onNodeWithTag("call-peer-avatar")
            .assertWidthIsEqualTo(224.dp)
            .assertHeightIsEqualTo(224.dp)
        composeRule.onNodeWithText("Алексей").assertExists()
        composeRule.onNodeWithText("00:03").assertExists()
    }

    @Test
    @Config(qualifiers = "ru-w411dp-h891dp")
    fun activeAudioCallUsesTheSameCompactAvatarAsIncomingCall() {
        render {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.5f)) {
                ActiveCallScreen(
                    peerName = "Алексей",
                    durationText = "00:03",
                    muted = false,
                    connectionHealth = ConnectionHealth.Good,
                    currentEndpoint = null,
                    availableEndpoints = emptyList(),
                    videoState = CallVideoState(allowed = false),
                    onMute = {},
                    onSelectEndpoint = {},
                    onCamera = {},
                    onSwitchCamera = {},
                    onVideoVisibilityChanged = {},
                    onEnd = {},
                )
            }
        }

        composeRule.onNodeWithTag("call-peer-avatar")
            .assertWidthIsEqualTo(168.dp)
            .assertHeightIsEqualTo(168.dp)
    }

    @Test
    fun endedCallScreenUsesTheSameCompactAvatarAsRingingScreens() {
        render {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.5f)) {
                EndedCallScreen(
                    peerName = "Алексей",
                    reason = CallEndReason.RemoteHangup,
                )
            }
        }

        composeRule.onNodeWithTag("call-peer-avatar")
            .assertWidthIsEqualTo(168.dp)
            .assertHeightIsEqualTo(168.dp)
    }

    @Test
    fun endedCallScreenUsesTheSameLargeAvatarAsRingingScreens() {
        render {
            EndedCallScreen(
                peerName = "Алексей",
                reason = CallEndReason.RemoteHangup,
            )
        }

        composeRule.onNodeWithTag("call-peer-avatar")
            .assertWidthIsEqualTo(224.dp)
            .assertHeightIsEqualTo(224.dp)
    }

    @Test
    fun allAudioCallScreensPassContactAddressToSharedAvatar() {
        val address = ContactAddress.of("https://calls.example", "alex")
        val reader = RecordingReader(address)

        val screen = mutableStateOf(0)
        val activity = render(reader) {
            when (screen.value) {
                0 -> IncomingCallScreen("call-1", "Алексей", address, "alex", onAnswer = {}, onReject = {})
                1 -> OutgoingCallScreen(
                    callee = "Алексей",
                    contactAddress = address,
                    fallbackLogin = "alex",
                    muted = false,
                    currentEndpoint = null,
                    availableEndpoints = emptyList(),
                    onMute = {},
                    onSelectEndpoint = {},
                    onCancel = {},
                )
                2 -> ActiveCallScreen(
                    peerName = "Алексей",
                    contactAddress = address,
                    fallbackLogin = "alex",
                    durationText = "00:03",
                    muted = false,
                    connectionHealth = ConnectionHealth.Good,
                    currentEndpoint = null,
                    availableEndpoints = emptyList(),
                    videoState = CallVideoState(allowed = false),
                    onMute = {},
                    onSelectEndpoint = {},
                    onCamera = {},
                    onSwitchCamera = {},
                    onVideoVisibilityChanged = {},
                    onEnd = {},
                )
                3 -> EndedCallScreen("Алексей", CallEndReason.RemoteHangup, address, "alex")
            }
        }

        try {
            for (index in 0..3) {
                if (index > 0) {
                    composeRule.runOnIdle {
                        reader.requested.clear()
                        screen.value = index
                    }
                }
                composeRule.onAllNodesWithTag("contact-avatar-photo", useUnmergedTree = true)
                    .assertCountEquals(1)
                assertEquals("Screen $index", setOf(address), reader.requested.toSet())
            }
        } finally {
            activity.pause().stop().destroy()
        }
    }

    private fun render(
        reader: ContactPhotoReader = NoPhotoReader,
        content: @androidx.compose.runtime.Composable () -> Unit,
    ): org.robolectric.android.controller.ActivityController<ComponentActivity> {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        composeRule.runOnUiThread {
            activity.get().setContent {
                TiniTalkTheme(darkTheme = true) {
                    CompositionLocalProvider(LocalContactPhotoReader provides reader) {
                        content()
                    }
                }
            }
        }
        return activity
    }

    private fun renderSecurity(
        security: CallSecurityState,
        videoState: CallVideoState<VideoRenderSource> = CallVideoState(allowed = false),
    ): org.robolectric.android.controller.ActivityController<ComponentActivity> = render {
        ActiveCallScreen(
            peerName = "Alice",
            durationText = "00:03",
            muted = false,
            connectionHealth = ConnectionHealth.Good,
            currentEndpoint = null,
            availableEndpoints = emptyList(),
            videoState = videoState,
            onMute = {},
            onSelectEndpoint = {},
            onCamera = {},
            onSwitchCamera = {},
            onVideoVisibilityChanged = {},
            onEnd = {},
            security = security,
        )
    }

    private object NoPhotoReader : ContactPhotoReader {
        override val revision: StateFlow<Long> = MutableStateFlow(0L)
        override fun peekBitmap(address: ContactAddress, targetPixels: Int): Bitmap? = null
        override fun loadBitmap(address: ContactAddress, targetPixels: Int): Bitmap? = null
    }

    private class RecordingReader(private val expected: ContactAddress) : ContactPhotoReader {
        private val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val requested = mutableListOf<ContactAddress>()
        override val revision: StateFlow<Long> = MutableStateFlow(0L)
        override fun peekBitmap(address: ContactAddress, targetPixels: Int): Bitmap? {
            requested += address
            return bitmap.takeIf { address == expected }
        }
        override fun loadBitmap(address: ContactAddress, targetPixels: Int): Bitmap? {
            requested += address
            return bitmap.takeIf { address == expected }
        }
    }
}
