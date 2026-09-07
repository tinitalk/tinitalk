package org.tinitalk.ui.call

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.tinitalk.call.CallVideoState
import org.tinitalk.call.ConnectionHealth
import org.tinitalk.call.ScreenShareState
import org.tinitalk.media.VideoRenderSource
import org.tinitalk.ui.theme.TiniTalkTheme
import org.junit.Assert.assertTrue
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
@Config(application = Application::class, sdk = [35], qualifiers = "w360dp-h800dp")
class ScreenSharingControlsTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun imageUsesOnlyTheSpaceBetweenSlidingPanels() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val progress = mutableFloatStateOf(1f)
        compose.runOnUiThread {
            activity.get().setContent {
                Box(Modifier.fillMaxSize().testTag("sharing-frame")) {
                    ScreenSharingPanels(progress,
                        header = { Box(Modifier.fillMaxWidth().height(100.dp).testTag("sharing-header")) },
                        controls = { Box(Modifier.fillMaxWidth().height(140.dp).testTag("sharing-controls")) },
                    ) { Box(Modifier.fillMaxSize().testTag("shared-image")) }
                }
            }
        }
        val headerHeight = compose.onNodeWithTag("sharing-header").fetchSemanticsNode().boundsInRoot.height
        val controlsHeight = compose.onNodeWithTag("sharing-controls").fetchSemanticsNode().boundsInRoot.height
        for (shown in listOf(1f, 0.5f, 0f, 0.5f, 1f)) {
            compose.runOnIdle { progress.floatValue = shown }
            val frame = compose.onNodeWithTag("sharing-frame").fetchSemanticsNode().boundsInRoot
            val image = compose.onNodeWithTag("shared-image").fetchSemanticsNode().boundsInRoot
            assertEquals(frame.top + headerHeight * shown, image.top, 0.5f)
            assertEquals(frame.bottom - controlsHeight * shown, image.bottom, 0.5f)
            assertEquals(frame.width, image.width, 0.5f)
        }
        activity.pause().stop().destroy()
    }

    @Test fun bothSharingRolesHideCameraAndKeepAudioActionsInOrder() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val screen = mutableStateOf(ScreenShareState(allowed = true, localId = "share"))
        compose.runOnUiThread {
            activity.get().setContent {
                TiniTalkTheme(darkTheme = true) {
                    ActiveCallScreen(
                        peerName = "Мама", durationText = "02:15", muted = false,
                        connectionHealth = ConnectionHealth.Good,
                        currentEndpoint = null, availableEndpoints = emptyList(),
                        // Even delayed camera callbacks must not bring camera controls back.
                        videoState = CallVideoState<VideoRenderSource>(
                            allowed = true, requested = true, sending = true,
                            remoteSending = true, screen = screen.value,
                        ),
                        onMute = {}, onSelectEndpoint = {}, onCamera = {},
                        onSwitchCamera = {}, onVideoVisibilityChanged = {}, onEnd = {},
                    )
                }
            }
        }
        for (receiving in listOf(false, true)) {
            if (receiving) compose.runOnIdle {
                screen.value = ScreenShareState(allowed = true, remoteId = "share", ready = true)
            }
            compose.onNodeWithText("Камера").assertDoesNotExist()
            compose.onNodeWithText("Показ экрана").assertDoesNotExist()
            compose.onNodeWithText("Показать экран").assertDoesNotExist()
            compose.onNodeWithText("Остановить показ").assertDoesNotExist()
            compose.onNodeWithText("Остановить").assertDoesNotExist()
            if (!receiving) {
                compose.onNodeWithContentDescription("Остановить показ экрана").assertIsDisplayed()
            } else {
                compose.onNodeWithContentDescription("Остановить показ экрана").assertDoesNotExist()
            }
            val positions = listOf("Звук", "Микрофон", "Завершить").map { label ->
                compose.onNodeWithText(label).assertIsDisplayed().fetchSemanticsNode().boundsInRoot.center.x
            }
            assertTrue(positions.zipWithNext().all { (left, right) -> left < right })
        }
        activity.pause().stop().destroy()
    }

    @Test fun idleSharingActionIsAnIconWithoutText() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        compose.runOnUiThread {
            activity.get().setContent {
                TiniTalkTheme(darkTheme = true) {
                    ActiveCallScreen(
                        peerName = "Мама", durationText = "02:15", muted = false,
                        connectionHealth = ConnectionHealth.Good,
                        currentEndpoint = null, availableEndpoints = emptyList(),
                        videoState = CallVideoState<VideoRenderSource>(
                            allowed = true,
                            screen = ScreenShareState(allowed = true),
                        ),
                        onMute = {}, onSelectEndpoint = {}, onCamera = {},
                        onSwitchCamera = {}, onVideoVisibilityChanged = {}, onEnd = {},
                    )
                }
            }
        }
        compose.onNodeWithContentDescription("Показать экран").assertIsDisplayed()
        compose.onNodeWithText("Показать экран").assertDoesNotExist()
        compose.onNodeWithText("Остановить показ").assertDoesNotExist()
        compose.onNodeWithText("Остановить").assertDoesNotExist()
        activity.pause().stop().destroy()
    }

    @Test fun unavailableSharingDoesNotShowAction() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        compose.runOnUiThread {
            activity.get().setContent {
                TiniTalkTheme(darkTheme = true) {
                    ActiveCallScreen(
                        peerName = "Мама", durationText = "02:15", muted = false,
                        connectionHealth = ConnectionHealth.Good,
                        currentEndpoint = null, availableEndpoints = emptyList(),
                        videoState = CallVideoState<VideoRenderSource>(
                            allowed = true,
                            screen = ScreenShareState(allowed = false),
                        ),
                        onMute = {}, onSelectEndpoint = {}, onCamera = {},
                        onSwitchCamera = {}, onVideoVisibilityChanged = {}, onEnd = {},
                    )
                }
            }
        }

        compose.onNodeWithContentDescription("Показать экран").assertDoesNotExist()
        compose.onNodeWithContentDescription("Остановить показ экрана").assertDoesNotExist()
        compose.onNodeWithText("Показать экран").assertDoesNotExist()
        compose.onNodeWithText("Остановить").assertDoesNotExist()
        activity.pause().stop().destroy()
    }

    @Test fun screenShareStartShowsCompactNotice() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val screen = mutableStateOf(ScreenShareState(allowed = true, localId = "share"))
        compose.runOnUiThread {
            activity.get().setContent {
                TiniTalkTheme(darkTheme = true) {
                    ActiveCallScreen(
                        peerName = "Мама", durationText = "02:15", muted = false,
                        connectionHealth = ConnectionHealth.Good,
                        currentEndpoint = null, availableEndpoints = emptyList(),
                        videoState = CallVideoState<VideoRenderSource>(
                            allowed = true,
                            screen = screen.value,
                        ),
                        onMute = {}, onSelectEndpoint = {}, onCamera = {},
                        onSwitchCamera = {}, onVideoVisibilityChanged = {}, onEnd = {},
                    )
                }
            }
        }

        compose.runOnIdle { screen.value = screen.value.copy(sending = true) }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Показ экрана начат").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Показ экрана начат").assertIsDisplayed()
        activity.pause().stop().destroy()
    }

    @Test fun stopScreenShareShowsCompactNotice() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        var stopRequests = 0
        compose.runOnUiThread {
            activity.get().setContent {
                TiniTalkTheme(darkTheme = true) {
                    ActiveCallScreen(
                        peerName = "Мама", durationText = "02:15", muted = false,
                        connectionHealth = ConnectionHealth.Good,
                        currentEndpoint = null, availableEndpoints = emptyList(),
                        videoState = CallVideoState<VideoRenderSource>(
                            allowed = true,
                            screen = ScreenShareState(allowed = true, localId = "share", sending = true),
                        ),
                        onMute = {}, onSelectEndpoint = {}, onCamera = {},
                        onSwitchCamera = {}, onVideoVisibilityChanged = {}, onEnd = {},
                        onStopSharing = { stopRequests++ },
                    )
                }
            }
        }

        compose.onNodeWithText("Остановить показ").assertDoesNotExist()
        compose.onNodeWithText("Остановить").assertDoesNotExist()
        compose.onNodeWithContentDescription("Остановить показ экрана").assertIsDisplayed().performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Показ экрана остановлен").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Показ экрана остановлен").assertIsDisplayed()
        assertEquals(1, stopRequests)
        activity.pause().stop().destroy()
    }

    @Test fun idleSharingActionDoesNotShiftCallContent() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val screenAllowed = mutableStateOf(false)
        compose.runOnUiThread {
            activity.get().setContent {
                TiniTalkTheme(darkTheme = true) {
                    ActiveCallScreen(
                        peerName = "Мама", durationText = "02:15", muted = false,
                        connectionHealth = ConnectionHealth.Good,
                        currentEndpoint = null, availableEndpoints = emptyList(),
                        videoState = CallVideoState<VideoRenderSource>(
                            allowed = true,
                            screen = ScreenShareState(allowed = screenAllowed.value),
                        ),
                        onMute = {}, onSelectEndpoint = {}, onCamera = {},
                        onSwitchCamera = {}, onVideoVisibilityChanged = {}, onEnd = {},
                    )
                }
            }
        }

        val avatarCenterBefore = compose.onNodeWithTag("call-peer-avatar")
            .fetchSemanticsNode().boundsInRoot.center.y
        compose.runOnIdle { screenAllowed.value = true }
        compose.onNodeWithContentDescription("Показать экран").assertIsDisplayed()
        val avatarCenterAfter = compose.onNodeWithTag("call-peer-avatar")
            .fetchSemanticsNode().boundsInRoot.center.y

        assertEquals(avatarCenterBefore, avatarCenterAfter, 0.5f)
        activity.pause().stop().destroy()
    }

    @Test fun cameraCallHidesIdleSharingAction() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        compose.runOnUiThread {
            activity.get().setContent {
                TiniTalkTheme(darkTheme = true) {
                    ActiveCallScreen(
                        peerName = "Мама", durationText = "02:15", muted = false,
                        connectionHealth = ConnectionHealth.Good,
                        currentEndpoint = null, availableEndpoints = emptyList(),
                        videoState = CallVideoState<VideoRenderSource>(
                            allowed = true,
                            requested = true,
                            sending = true,
                            screen = ScreenShareState(allowed = true),
                        ),
                        onMute = {}, onSelectEndpoint = {}, onCamera = {},
                        onSwitchCamera = {}, onVideoVisibilityChanged = {}, onEnd = {},
                    )
                }
            }
        }
        compose.onNodeWithContentDescription("Показать экран").assertDoesNotExist()
        compose.onNodeWithContentDescription("Остановить показ экрана").assertDoesNotExist()
        compose.onNodeWithText("Остановить показ").assertDoesNotExist()
        compose.onNodeWithText("Остановить").assertDoesNotExist()
        activity.pause().stop().destroy()
    }
}
