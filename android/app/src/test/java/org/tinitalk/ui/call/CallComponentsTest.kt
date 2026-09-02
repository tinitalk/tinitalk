package org.tinitalk.ui.call

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.tinitalk.call.CallEndReason
import org.tinitalk.call.CallVideoState
import org.tinitalk.call.ConnectionHealth
import org.tinitalk.data.ContactAddress
import org.tinitalk.data.ContactPhotoReader
import org.tinitalk.ui.LocalContactPhotoReader
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
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class CallComponentsTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

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
