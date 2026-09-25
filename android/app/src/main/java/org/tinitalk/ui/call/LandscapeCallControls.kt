package org.tinitalk.ui.call

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import org.tinitalk.R
import org.tinitalk.i18n.appString
import org.tinitalk.telecom.AudioEndpoint
import org.tinitalk.ui.theme.CallRejectRed

internal val LandscapeCallControlsWidth = 96.dp
internal val CallControlsOverlayBackground = Color.Black.copy(alpha = 0.34f)

/** The same two-by-two layout for outgoing and active audio calls. */
@Composable
internal fun LandscapeCallControlGrid(
    muted: Boolean,
    currentEndpoint: AudioEndpoint?,
    availableEndpoints: List<AudioEndpoint>,
    cameraVisible: Boolean,
    cameraEnabled: Boolean,
    cameraRequested: Boolean,
    onMute: (Boolean) -> Unit,
    onSelectEndpoint: (AudioEndpoint) -> Unit,
    onShowRoutePicker: () -> Unit,
    onCamera: (Boolean) -> Unit,
    onEnd: () -> Unit,
    endLabel: String = appString(R.string.text_end_call_98),
) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val gap = 24.dp
        val buttonSize = minOf(88.dp, (maxWidth - gap) / 2, (maxHeight - gap) / 2)
            .coerceAtLeast(48.dp)
        CompositionLocalProvider(LocalCallActionLabelsVisible provides false) {
            Column(Modifier.testTag("landscape-call-grid"), verticalArrangement = Arrangement.spacedBy(gap)) {
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    Box(Modifier.size(buttonSize), contentAlignment = Alignment.Center) {
                        if (cameraVisible) CameraCallAction(cameraRequested, Modifier, onCamera,
                            buttonSize = buttonSize, enabled = cameraEnabled)
                    }
                    AudioRouteAction(currentEndpoint, availableEndpoints, onSelectEndpoint = onSelectEndpoint,
                        onShowPicker = onShowRoutePicker, buttonSize = buttonSize)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    MuteCallAction(muted, onMute = onMute, buttonSize = buttonSize)
                    RoundCallAction(label = endLabel, color = CallRejectRed, onClick = onEnd,
                        iconRotation = 135f, buttonSize = buttonSize)
                }
            }
        }
    }
}

/** Visible media controls are distributed over the full safe height of the right rail. */
@Composable
internal fun LandscapeCallControls(
    muted: Boolean,
    currentEndpoint: AudioEndpoint?,
    availableEndpoints: List<AudioEndpoint>,
    cameraVisible: Boolean,
    cameraEnabled: Boolean,
    cameraRequested: Boolean,
    onMute: (Boolean) -> Unit,
    onSelectEndpoint: (AudioEndpoint) -> Unit,
    onShowRoutePicker: () -> Unit,
    onCamera: (Boolean) -> Unit,
    onEnd: () -> Unit,
    modifier: Modifier = Modifier,
    backgroundColor: Color = Color.Transparent,
    switchCameraVisible: Boolean = false,
    switchCameraEnabled: Boolean = false,
    onSwitchCamera: () -> Unit = {},
) {
    val panelInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.End)
    val endInset = panelInsets.asPaddingValues().calculateEndPadding(LocalLayoutDirection.current)
    BoxWithConstraints(modifier.width(LandscapeCallControlsWidth + endInset).fillMaxHeight()
        .background(backgroundColor).testTag("landscape-call-panel-background")
        .windowInsetsPadding(panelInsets).padding(vertical = 4.dp)) {
        val actionCount = 3 + (if (cameraVisible) 1 else 0) + (if (switchCameraVisible) 1 else 0)
        val buttonSize = minOf(72.dp, maxWidth - 16.dp, maxHeight / actionCount - 8.dp)
            .coerceAtLeast(48.dp)
        CompositionLocalProvider(LocalCallActionLabelsVisible provides false) {
            Column(Modifier.fillMaxSize().testTag("landscape-call-controls")) {
                if (switchCameraVisible) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    SwitchCameraCallAction(switchCameraEnabled, Modifier,
                        onSwitchCamera, buttonSize)
                }
                if (cameraVisible) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CameraCallAction(cameraRequested, Modifier, onCamera,
                        buttonSize = buttonSize, enabled = cameraEnabled)
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    AudioRouteAction(currentEndpoint, availableEndpoints, onSelectEndpoint = onSelectEndpoint,
                        onShowPicker = onShowRoutePicker, buttonSize = buttonSize)
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    MuteCallAction(muted, onMute = onMute, buttonSize = buttonSize)
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    EndCallAction(Modifier, onEnd, buttonSize = buttonSize)
                }
            }
        }
    }
}
