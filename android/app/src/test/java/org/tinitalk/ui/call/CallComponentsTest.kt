package org.tinitalk.ui.call

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
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
    fun securityCodeIsDisplayedWithoutConfirmationAction() {
        val activity = renderSecurity(CallSecurityState.Ready("4821 7034 1596"))

        composeRule.onNodeWithTag("security_code", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("🏰 🍆 🧵 🛷 🧬").assertExists()
        composeRule.onNodeWithText("Сверьте весь код голосом").assertDoesNotExist()
        composeRule.onAllNodesWithTag("security_code_confirm").assertCountEquals(0)
        composeRule.onNodeWithTag("security_code_panel").performClick()
        composeRule.onNodeWithText("Код безопасности").assertExists()
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

        composeRule.onNodeWithText("Соединение небезопасно").performClick()
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

        composeRule.onNodeWithText("Не удаётся подтвердить безопасность соединения").performClick()
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
    @Config(qualifiers = "w320dp-h480dp")
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

        composeRule.onNodeWithText("Звук").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Выбрать звук").assertExists()

        activity.pause().stop().destroy()
    }

    @Test
    fun muteActionKeepsActionDescriptionForBothStates() {
        val muted = mutableStateOf(false)
        val activity = render {
            MuteCallAction(muted = muted.value, onMute = { muted.value = it })
        }

        composeRule.onNodeWithContentDescription("Выключить микрофон").assertExists()
        composeRule.runOnUiThread { muted.value = true }
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("Включить микрофон").assertExists()
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
    @Config(qualifiers = "w411dp-h891dp")
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
    @Config(qualifiers = "w411dp-h891dp")
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
        composeRule.onNodeWithContentDescription("Прямое соединение").assertExists()
        assertEquals(
            initialAvatarY,
            composeRule.onNodeWithTag("call-peer-avatar").fetchSemanticsNode().boundsInRoot.center.y,
            0.01f,
        )

        composeRule.runOnUiThread { route.value = CallTransportRoute.Turn }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Соединение через TURN").assertExists()
        assertEquals(
            initialAvatarY,
            composeRule.onNodeWithTag("call-peer-avatar").fetchSemanticsNode().boundsInRoot.center.y,
            0.01f,
        )
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
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
    @Config(qualifiers = "w411dp-h891dp")
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

        composeRule.onNodeWithContentDescription("Включить камеру").assertIsNotEnabled()
        composeRule.onNodeWithText("Камера").assertExists()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
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

        composeRule.onNodeWithContentDescription("Включить камеру").assertIsNotEnabled()
        composeRule.onNodeWithText("Камера").assertExists()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
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
    @Config(qualifiers = "w411dp-h891dp")
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

        render(reader) {
            IncomingCallScreen("call-1", "Алексей", address, "alex", onAnswer = {}, onReject = {})
            OutgoingCallScreen(
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
            ActiveCallScreen(
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
            EndedCallScreen("Алексей", CallEndReason.RemoteHangup, address, "alex")
        }

        composeRule.waitUntil(timeoutMillis = 5_000) { reader.requested.size >= 4 }
        assertEquals(setOf(address), reader.requested.toSet())
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
