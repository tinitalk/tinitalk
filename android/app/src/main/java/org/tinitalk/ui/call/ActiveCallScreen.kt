package org.tinitalk.ui.call

import org.tinitalk.i18n.appString

import android.content.Context
import androidx.core.content.edit
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.core.telecom.CallEndpointCompat
import org.tinitalk.R
import org.tinitalk.call.CallVideoState
import org.tinitalk.call.CallEndReason
import org.tinitalk.call.CameraFacing
import org.tinitalk.call.ConnectionHealth
import org.tinitalk.call.CallTransportRoute
import org.tinitalk.call.CallSecurityFailureReason
import org.tinitalk.call.CallSecurityState
import org.tinitalk.call.CallSecurityUnavailableReason
import org.tinitalk.call.CallSecurityEmoji
import org.tinitalk.data.ContactAddress
import org.tinitalk.media.VideoRenderSource
import org.tinitalk.telecom.AudioEndpoint
import org.tinitalk.ui.ContactAvatar
import org.tinitalk.ui.theme.CallBackgroundBottom
import org.tinitalk.ui.theme.CallBackgroundTop
import org.tinitalk.ui.theme.CallRejectRed
import org.tinitalk.ui.theme.BrandGold
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val SecurityEmojiFont = FontFamily(Font(R.font.twemoji_security_256))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActiveCallScreen(
    peerName: String,
    contactAddress: ContactAddress? = null,
    fallbackLogin: String = peerName,
    durationText: String,
    muted: Boolean,
    connectionHealth: ConnectionHealth,
    transportRoute: CallTransportRoute = CallTransportRoute.Unknown,
    currentEndpoint: AudioEndpoint?,
    availableEndpoints: List<AudioEndpoint>,
    videoState: CallVideoState<VideoRenderSource>,
    onMute: (Boolean) -> Unit,
    onSelectEndpoint: (AudioEndpoint) -> Unit,
    onCamera: (Boolean) -> Unit,
    onSwitchCamera: () -> Unit,
    onVideoVisibilityChanged: (Boolean) -> Unit,
    onEnd: () -> Unit,
    onShareScreen: () -> Unit = {},
    onStopSharing: () -> Unit = {},
    security: CallSecurityState = CallSecurityState.Establishing,
) {
    var routePickerVisible by remember { mutableStateOf(false) }
    var confirmSharing by remember(videoState.callKey) { mutableStateOf(false) }
    var sharingError by remember(videoState.callKey) { mutableStateOf<String?>(null) }
    var sharingNoticeText by remember(videoState.callKey) { mutableStateOf("") }
    var sharingNoticeVisible by remember(videoState.callKey) { mutableStateOf(false) }
    var sharingNoticeId by remember(videoState.callKey) { mutableIntStateOf(0) }
    val showSharingNotice: (String) -> Unit = { message ->
        sharingNoticeText = message
        sharingNoticeVisible = true
        sharingNoticeId += 1
    }
    var observedScreenSending by remember(videoState.callKey) { mutableStateOf(videoState.screen.sending) }
    val screen = videoState.screen
    val receivingScreen = screen.remoteId != null
    LaunchedEffect(screen.failure) { sharingError = screen.failure }
    LaunchedEffect(videoState.callKey, screen.sending) {
        val wasSending = observedScreenSending
        observedScreenSending = screen.sending
        if (screen.sending && !wasSending) {
            showSharingNotice(appString(R.string.text_screen_sharing_started_129))
        }
    }
    LaunchedEffect(sharingNoticeId) {
        if (sharingNoticeId == 0) return@LaunchedEffect
        delay(2_000)
        sharingNoticeVisible = false
    }
    val status = when (connectionHealth) {
        ConnectionHealth.Connecting -> appString(R.string.text_connecting_130)
        ConnectionHealth.Reconnecting -> appString(R.string.text_reconnecting_131)
        ConnectionHealth.Poor -> appString(R.string.text_weak_connection_132)
        else -> appString(R.string.text_in_a_call_133)
    }
    val statusColor = if (connectionHealth == ConnectionHealth.Poor || connectionHealth == ConnectionHealth.Reconnecting) {
        Color(0xFFFFCA6A)
    } else {
        Color.White.copy(alpha = 0.76f)
    }
    val videoMode = videoModeActive(
        videoAllowed = videoState.allowed && !screen.active,
        localSending = videoState.sending,
        remoteSending = videoState.remoteSending,
    )
    val cameraActive = videoState.requested || videoState.sending || videoState.remoteSending
    val sharingPanelVisible = screen.allowed && !receivingScreen && (screen.requested || !cameraActive)
    val cameraPressed: (Boolean) -> Unit = { requested ->
        speakerRouteOnCameraPress(requested, currentEndpoint, availableEndpoints)?.let(onSelectEndpoint)
        onCamera(requested)
    }

    LaunchedEffect(videoState.callId, videoMode, screen.requested, receivingScreen) {
        if (videoMode || screen.requested || receivingScreen) {
            speakerRouteOnCameraPress(true, currentEndpoint, availableEndpoints)?.let(onSelectEndpoint)
        }
    }

    Box(Modifier.fillMaxSize().background(CallBackgroundTop)) {
        if (receivingScreen) {
            ScreenSharingViewer(peerName, durationText, connectionHealth, videoState, muted,
                currentEndpoint, availableEndpoints, onMute, onSelectEndpoint,
                { routePickerVisible = true }, routePickerVisible, onVideoVisibilityChanged, onEnd)
        } else if (videoMode) {
            VideoActiveCallScreen(
                peerName = peerName,
                contactAddress = contactAddress,
                fallbackLogin = fallbackLogin,
                durationText = durationText,
                status = status,
                statusColor = statusColor,
                muted = muted,
                currentEndpoint = currentEndpoint,
                availableEndpoints = availableEndpoints,
                videoState = videoState,
                onMute = onMute,
                onSelectEndpoint = onSelectEndpoint,
                onShowRoutePicker = { routePickerVisible = true },
                onCamera = cameraPressed,
                onSwitchCamera = onSwitchCamera,
                onVideoVisibilityChanged = onVideoVisibilityChanged,
                onEnd = onEnd,
            )
        } else {
            AudioActiveCallScreen(
                peerName = peerName,
                contactAddress = contactAddress,
                fallbackLogin = fallbackLogin,
                durationText = durationText,
                status = status,
                statusColor = statusColor,
                transportRoute = transportRoute,
                security = security,
                muted = muted,
                currentEndpoint = currentEndpoint,
                availableEndpoints = availableEndpoints,
                videoAllowed = videoState.allowed && !screen.active,
                cameraActionVisible = !screen.active,
                cameraRequested = videoState.requested,
                onMute = onMute,
                onSelectEndpoint = onSelectEndpoint,
                onShowRoutePicker = { routePickerVisible = true },
                onCamera = cameraPressed,
                onEnd = onEnd,
            )
        }
        if (sharingPanelVisible) {
            val sharingActionEnabled = screen.requested ||
                connectionHealth == ConnectionHealth.Good ||
                connectionHealth == ConnectionHealth.Poor
            val sharingStatusText = when {
                !screen.requested -> null
                videoState.networkGated -> appString(R.string.text_sharing_paused_reconnecting_134)
                screen.sending -> null
                else -> appString(R.string.text_preparing_to_share_135)
            }
            ScreenShareActionOverlay(
                requested = screen.requested,
                enabled = sharingActionEnabled,
                statusText = sharingStatusText,
                onStart = { confirmSharing = true },
                onStop = {
                    onStopSharing()
                    showSharingNotice(appString(R.string.text_screen_sharing_stopped_48))
                },
                modifier = Modifier.align(Alignment.TopEnd).then(
                    if (org.tinitalk.ui.compactLandscape()) Modifier.navigationBarsPadding() else Modifier),
            )
        }
        AnimatedVisibility(
            visible = sharingNoticeVisible,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 132.dp),
            enter = fadeIn(tween(150)) + slideInVertically(tween(180)) { it / 2 },
            exit = fadeOut(tween(150)) + slideOutVertically(tween(180)) { it / 2 },
        ) {
            Text(
                text = sharingNoticeText,
                modifier = Modifier
                    .widthIn(max = 360.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(Color(0xFF4D4D52))
                    .padding(horizontal = 24.dp, vertical = 14.dp),
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    if (confirmSharing) AlertDialog(
        onDismissRequest = { confirmSharing = false },
        title = { Text(appString(R.string.text_share_your_screen_136)) },
        text = { Text(appString(R.string.text_the_other_person_will_see_the_selected_app_or_your_entire_screen__137)) },
        confirmButton = { TextButton(onClick = { confirmSharing = false; onShareScreen() }) { Text(appString(R.string.text_continue_138)) } },
        dismissButton = { TextButton(onClick = { confirmSharing = false }) { Text(appString(R.string.text_cancel_12)) } },
    )
    sharingError?.let { message -> AlertDialog(
        onDismissRequest = { sharingError = null },
        title = { Text(appString(R.string.text_screen_sharing_139)) }, text = { Text(message) },
        confirmButton = { TextButton(onClick = { sharingError = null }) { Text(appString(R.string.text_ok_61)) } },
    ) }

    AudioRoutePicker(
        visible = routePickerVisible,
        currentEndpoint = currentEndpoint,
        availableEndpoints = availableEndpoints,
        onDismiss = { routePickerVisible = false },
        onSelectEndpoint = onSelectEndpoint,
    )
}

@Composable
private fun SecurityCodePanel(
    security: CallSecurityState,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    var detailsVisible by remember { mutableStateOf(false) }
    val accent = when (security) {
        is CallSecurityState.Unavailable -> Color(0xFFFFCA6A)
        is CallSecurityState.Failed -> CallRejectRed
        CallSecurityState.Establishing -> Color.White.copy(alpha = 0.7f)
        is CallSecurityState.Ready -> BrandGold
    }
    val messageStyle = MaterialTheme.typography.titleMedium.copy(
        fontSize = if (compact) 16.sp else 18.sp,
        lineHeight = if (compact) 20.sp else 22.sp,
        fontWeight = FontWeight.SemiBold,
    )
    Column(
        modifier = modifier
            .testTag("security_code_panel")
            .widthIn(max = 360.dp)
            .fillMaxWidth()
            .clickable(enabled = security != CallSecurityState.Establishing) { detailsVisible = true }
            .padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (security) {
            is CallSecurityState.Unavailable -> Text(
                appString(R.string.text_cannot_verify_connection_security_140),
                modifier = Modifier.fillMaxWidth(),
                color = accent,
                style = messageStyle,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            CallSecurityState.Establishing -> Text(
                appString(R.string.text_checking_connection_security_141),
                modifier = Modifier.fillMaxWidth(),
                color = accent,
                style = messageStyle,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            is CallSecurityState.Failed -> Text(
                appString(R.string.text_connection_is_not_secure_142),
                modifier = Modifier.fillMaxWidth(),
                color = accent,
                style = messageStyle,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            is CallSecurityState.Ready -> {
                val emoji = remember(security.code) {
                    CallSecurityEmoji.fromNumericCode(security.code).joinToString(" ")
                }
                Text(
                    emoji,
                    modifier = Modifier
                        .testTag("security_code")
                        .semantics { contentDescription = appString(R.string.text_security_code_value_143, emoji) },
                    color = Color.White,
                    fontFamily = SecurityEmojiFont,
                    fontSize = if (compact) 26.sp else 29.sp,
                    maxLines = 1,
                )
            }
        }
    }
    if (detailsVisible) SecurityCodeDetailsDialog(security) { detailsVisible = false }
}

@Composable
private fun SecurityCodeDetailsDialog(
    security: CallSecurityState,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        modifier = Modifier.testTag("security_code_dialog"),
        onDismissRequest = onDismiss,
        icon = if (security is CallSecurityState.Failed) {
            {
                Icon(
                    painter = painterResource(R.drawable.ic_server_incompatible),
                    contentDescription = appString(R.string.text_security_warning_144),
                    modifier = Modifier
                        .size(56.dp)
                        .testTag("security_code_error_icon"),
                    tint = CallRejectRed,
                )
            }
        } else {
            null
        },
        title = { Text(securityDetailsTitle(security), textAlign = TextAlign.Center) },
        text = { Text(securityDetailsText(security)) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(appString(R.string.text_ok_61)) } },
    )
}

private fun securityDetailsTitle(security: CallSecurityState): String = when (security) {
    CallSecurityState.Establishing -> appString(R.string.text_checking_connection_security_145)
    is CallSecurityState.Ready -> appString(R.string.text_security_code_146)
    is CallSecurityState.Unavailable -> appString(R.string.text_cannot_verify_security_147)
    is CallSecurityState.Failed -> appString(R.string.text_connection_is_not_secure_142)
}

private fun securityDetailsText(security: CallSecurityState): String = when (security) {
    CallSecurityState.Establishing ->
        appString(R.string.text_the_phones_exchange_temporary_keys_and_verify_webrtc_certificates_148)
    is CallSecurityState.Ready ->
        appString(R.string.text_compare_all_5_emoji_with_the_other_person_if_they_match_the_conne_149) +
            appString(R.string.text_if_even_one_emoji_is_different_end_the_call_150)
    is CallSecurityState.Unavailable -> when (security.reason) {
        CallSecurityUnavailableReason.ServerUnsupported ->
            appString(R.string.text_the_tinitalk_server_is_out_of_date_the_app_cannot_verify_the_secu_151)
        CallSecurityUnavailableReason.PeerUnsupported ->
            appString(R.string.text_the_other_person_s_app_is_out_of_date_the_security_of_this_call_c_152)
    }
    is CallSecurityState.Failed -> securityFailureText(security.reason) +
        appString(R.string.text_n_nend_the_call_and_do_not_share_confidential_information_153)
}

private fun securityFailureText(reason: CallSecurityFailureReason): String = when (reason) {
    CallSecurityFailureReason.ExchangeTimeout ->
        appString(R.string.text_the_security_check_timed_out_the_call_is_not_secure_154)
    CallSecurityFailureReason.TransportTimeout ->
        appString(R.string.text_a_secure_connection_was_not_established_in_time_the_call_is_not_s_155)
    CallSecurityFailureReason.TransportFailed ->
        appString(R.string.text_could_not_establish_a_secure_connection_the_call_is_not_secure_156)
    CallSecurityFailureReason.UnexpectedMessage ->
        appString(R.string.text_verification_data_arrived_out_of_order_or_was_corrupted_the_call__157)
    CallSecurityFailureReason.InvalidFingerprint ->
        appString(R.string.text_the_connection_certificate_is_invalid_the_call_is_not_secure_158)
    CallSecurityFailureReason.FingerprintMismatch ->
        appString(R.string.text_the_connection_certificate_does_not_match_the_verification_data_t_159)
    CallSecurityFailureReason.CommitmentMismatch ->
        appString(R.string.text_verification_data_changed_after_the_call_started_the_call_is_not__160)
    CallSecurityFailureReason.FingerprintChanged ->
        appString(R.string.text_the_connection_certificate_changed_during_the_call_the_call_is_no_161)
    CallSecurityFailureReason.InvalidPublicKey ->
        appString(R.string.text_an_invalid_security_key_was_received_the_call_is_not_secure_162)
    CallSecurityFailureReason.InternalError ->
        appString(R.string.text_the_security_check_failed_the_call_is_not_secure_163)
}

@Composable
private fun ScreenShareActionOverlay(
    requested: Boolean,
    enabled: Boolean,
    statusText: String?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .statusBarsPadding()
            .padding(top = 8.dp, end = 12.dp),
        horizontalAlignment = Alignment.End,
    ) {
        if (requested) {
            RoundCallAction(
                label = appString(R.string.text_screen_sharing_139),
                contentDescription = appString(R.string.text_stop_screen_sharing_164),
                color = Color(0xFF315EA8),
                enabled = enabled,
                onClick = onStop,
                iconResource = R.drawable.ic_screen_share,
                buttonSize = 56.dp,
                iconSize = 32.dp,
                showLabel = false,
            )
        } else {
            IconButton(
                onClick = onStart,
                enabled = enabled,
                modifier = Modifier.size(56.dp),
            ) {
                Icon(
                    painterResource(R.drawable.ic_screen_share),
                    contentDescription = appString(R.string.text_share_screen_165),
                    tint = Color.White.copy(alpha = if (enabled) 0.68f else 0.28f),
                    modifier = Modifier.size(32.dp),
                )
            }
        }
        if (statusText != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = statusText,
                modifier = Modifier
                    .widthIn(max = 260.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.Black.copy(alpha = 0.48f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                color = Color.White.copy(alpha = 0.72f),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.End,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AudioActiveCallScreen(
    peerName: String,
    contactAddress: ContactAddress?,
    fallbackLogin: String,
    durationText: String,
    status: String,
    statusColor: Color,
    transportRoute: CallTransportRoute,
    security: CallSecurityState,
    muted: Boolean,
    currentEndpoint: AudioEndpoint?,
    availableEndpoints: List<AudioEndpoint>,
    videoAllowed: Boolean,
    cameraActionVisible: Boolean,
    cameraRequested: Boolean,
    onMute: (Boolean) -> Unit,
    onSelectEndpoint: (AudioEndpoint) -> Unit,
    onShowRoutePicker: () -> Unit,
    onCamera: (Boolean) -> Unit,
    onEnd: () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val layout = callControlLayout(
            videoAllowed = videoAllowed,
            videoModeActive = false,
            widthDp = (maxWidth.value - 40f).coerceAtLeast(0f),
            heightDp = (maxHeight.value - SecurityPanelReservedHeightDp).coerceAtLeast(0f),
            fontScale = LocalDensity.current.fontScale,
            cameraActionVisible = cameraActionVisible,
        )
        if (layout.scrollable && !org.tinitalk.ui.compactLandscape()) {
            ConstrainedAudioActiveCallScreen(
                peerName = peerName,
                contactAddress = contactAddress,
                fallbackLogin = fallbackLogin,
                durationText = durationText,
                status = status,
                statusColor = statusColor,
                transportRoute = transportRoute,
                security = security,
                muted = muted,
                currentEndpoint = currentEndpoint,
                availableEndpoints = availableEndpoints,
                layout = layout,
                shortScreen = maxHeight.value < ShortAudioScreenHeightDp,
                videoAllowed = videoAllowed,
                cameraRequested = cameraRequested,
                onMute = onMute,
                onSelectEndpoint = onSelectEndpoint,
                onShowRoutePicker = onShowRoutePicker,
                onCamera = onCamera,
                onEnd = onEnd,
            )
        } else {
            RegularAudioActiveCallScreen(
                peerName = peerName,
                contactAddress = contactAddress,
                fallbackLogin = fallbackLogin,
                durationText = durationText,
                status = status,
                statusColor = statusColor,
                transportRoute = transportRoute,
                security = security,
                muted = muted,
                currentEndpoint = currentEndpoint,
                availableEndpoints = availableEndpoints,
                layout = layout,
                videoAllowed = videoAllowed,
                cameraRequested = cameraRequested,
                onMute = onMute,
                onSelectEndpoint = onSelectEndpoint,
                onShowRoutePicker = onShowRoutePicker,
                onCamera = onCamera,
                onEnd = onEnd,
            )
        }
    }
}

@Composable
private fun RegularAudioActiveCallScreen(
    peerName: String,
    contactAddress: ContactAddress?,
    fallbackLogin: String,
    durationText: String,
    status: String,
    statusColor: Color,
    transportRoute: CallTransportRoute,
    security: CallSecurityState,
    muted: Boolean,
    currentEndpoint: AudioEndpoint?,
    availableEndpoints: List<AudioEndpoint>,
    layout: CallControlLayout,
    videoAllowed: Boolean,
    cameraRequested: Boolean,
    onMute: (Boolean) -> Unit,
    onSelectEndpoint: (AudioEndpoint) -> Unit,
    onShowRoutePicker: () -> Unit,
    onCamera: (Boolean) -> Unit,
    onEnd: () -> Unit,
) {
    CallScreenSurface(
        status = status,
        peerName = peerName,
        contactAddress = contactAddress,
        fallbackLogin = fallbackLogin,
        detail = durationText,
        detailAccessory = {
            SecurityCodePanel(
                security = security,
                modifier = Modifier.padding(top = 10.dp),
            )
        },
        statusColor = statusColor,
        statusAccessory = { CallTransportRouteIndicator(transportRoute) },
        prominentAvatar = true,
        landscapeControls = {
            LandscapeCallControlGrid(muted, currentEndpoint, availableEndpoints,
                cameraVisible = CallControlAction.Camera in layout.actions,
                cameraEnabled = videoAllowed, cameraRequested = cameraRequested,
                onMute = onMute, onSelectEndpoint = onSelectEndpoint,
                onShowRoutePicker = onShowRoutePicker, onCamera = onCamera, onEnd = onEnd)
        },
    ) {
        Text(
            text = appString(R.string.text_audio_value_166, audioEndpointLabel(currentEndpoint)),
            color = Color.White.copy(alpha = 0.68f),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(14.dp))
        AdaptiveAudioControls(
            muted = muted,
            currentEndpoint = currentEndpoint,
            availableEndpoints = availableEndpoints,
            layout = layout,
            videoAllowed = videoAllowed,
            cameraRequested = cameraRequested,
            onMute = onMute,
            onSelectEndpoint = onSelectEndpoint,
            onShowRoutePicker = onShowRoutePicker,
            onCamera = onCamera,
            onEnd = onEnd,
        )
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun ConstrainedAudioActiveCallScreen(
    peerName: String,
    contactAddress: ContactAddress?,
    fallbackLogin: String,
    durationText: String,
    status: String,
    statusColor: Color,
    transportRoute: CallTransportRoute,
    security: CallSecurityState,
    muted: Boolean,
    currentEndpoint: AudioEndpoint?,
    availableEndpoints: List<AudioEndpoint>,
    layout: CallControlLayout,
    shortScreen: Boolean,
    videoAllowed: Boolean,
    cameraRequested: Boolean,
    onMute: (Boolean) -> Unit,
    onSelectEndpoint: (AudioEndpoint) -> Unit,
    onShowRoutePicker: () -> Unit,
    onCamera: (Boolean) -> Unit,
    onEnd: () -> Unit,
) {
    val avatarSize = if (shortScreen) {
        ShortAudioScreenAvatarSize
    } else {
        prominentCallAvatarSize(LocalDensity.current.fontScale)
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(CallBackgroundTop, CallBackgroundBottom))),
    ) {
        VideoFallbackContent(
            peerName = peerName,
            contactAddress = contactAddress,
            fallbackLogin = fallbackLogin,
            avatarSize = avatarSize,
        )
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = status,
                color = statusColor,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            CallTransportRouteIndicator(transportRoute)
            Text(
                text = peerName,
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = durationText,
                color = Color.White.copy(alpha = 0.78f),
                style = MaterialTheme.typography.titleSmall,
            )
            SecurityCodePanel(
                security = security,
                compact = true,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(CallControlsOverlayBackground)
                .navigationBarsPadding()
                .heightIn(max = layout.viewportHeightDp.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = appString(R.string.text_audio_value_166, audioEndpointLabel(currentEndpoint)),
                color = Color.White.copy(alpha = 0.72f),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            AdaptiveAudioControls(
                muted = muted,
                currentEndpoint = currentEndpoint,
                availableEndpoints = availableEndpoints,
                layout = layout,
                videoAllowed = videoAllowed,
                cameraRequested = cameraRequested,
                onMute = onMute,
                onSelectEndpoint = onSelectEndpoint,
                onShowRoutePicker = onShowRoutePicker,
                onCamera = onCamera,
                onEnd = onEnd,
            )
        }
    }
}

@Composable
private fun CallTransportRouteIndicator(
    route: CallTransportRoute,
    modifier: Modifier = Modifier,
) {
    val description = when (route) {
        CallTransportRoute.Unknown -> null
        CallTransportRoute.Direct -> appString(R.string.text_direct_connection_167)
        CallTransportRoute.Turn -> appString(R.string.text_connection_via_turn_168)
    }
    val accessibility = if (description == null) {
        Modifier
    } else {
        Modifier.semantics { contentDescription = description }
    }
    Box(
        modifier = modifier
            .width(96.dp)
            .height(18.dp)
            .then(accessibility)
            .testTag("call-transport-route"),
        contentAlignment = Alignment.Center,
    ) {
        if (route == CallTransportRoute.Unknown) return@Box
        Row(
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RouteIcon(R.drawable.ic_call)
            RouteIcon(R.drawable.ic_route_bidirectional, width = 16.dp, height = 10.dp)
            if (route == CallTransportRoute.Turn) {
                RouteIcon(R.drawable.ic_server_route, tint = BrandGold.copy(alpha = 0.9f))
                RouteIcon(R.drawable.ic_route_bidirectional, width = 16.dp, height = 10.dp)
            }
            RouteIcon(R.drawable.ic_call)
        }
    }
}

@Composable
private fun RouteIcon(
    iconResource: Int,
    width: Dp = 14.dp,
    height: Dp = 14.dp,
    tint: Color = Color.White.copy(alpha = 0.58f),
) {
    Icon(
        painter = painterResource(iconResource),
        contentDescription = null,
        tint = tint,
        modifier = Modifier.width(width).height(height),
    )
}

@Composable
private fun VideoActiveCallScreen(
    peerName: String,
    contactAddress: ContactAddress?,
    fallbackLogin: String,
    durationText: String,
    status: String,
    statusColor: Color,
    muted: Boolean,
    currentEndpoint: AudioEndpoint?,
    availableEndpoints: List<AudioEndpoint>,
    videoState: CallVideoState<VideoRenderSource>,
    onMute: (Boolean) -> Unit,
    onSelectEndpoint: (AudioEndpoint) -> Unit,
    onShowRoutePicker: () -> Unit,
    onCamera: (Boolean) -> Unit,
    onSwitchCamera: () -> Unit,
    onVideoVisibilityChanged: (Boolean) -> Unit,
    onEnd: () -> Unit,
) {
    val landscape = org.tinitalk.ui.compactLandscape()
    val localSource = videoState.localTrack
    val remoteSource = videoState.remoteTrack
    var localFrameVisible by remember(localSource) { mutableStateOf(false) }
    var localFrameSize by remember(localSource) { mutableStateOf(0 to 0) }
    var remoteFrameVisible by remember(remoteSource) { mutableStateOf(false) }
    var remoteFrameSize by remember(remoteSource) { mutableStateOf(0 to 0) }
    var remoteVideoWasVisible by remember(videoState.callId, videoState.remoteSending) {
        mutableStateOf(false)
    }
    val showVideoRecoveryOverlay = videoRecoveryOverlayVisible(
        remoteSending = videoState.remoteSending,
        remoteVideoWasVisible = remoteVideoWasVisible,
        remoteFrameVisible = remoteFrameVisible,
    )
    val presentation = videoCallPresentation(
        videoAllowed = videoState.allowed,
        cameraRequested = videoState.requested,
        localFrameVisible = localFrameVisible,
        remoteFrameVisible = remoteFrameVisible,
    )
    val controlsMayAutoHide = presentation.controlsMayAutoHide
    val context = LocalContext.current
    val previewPreferences = remember(context) {
        context.applicationContext.getSharedPreferences(SelfPreviewPreferencesName, Context.MODE_PRIVATE)
    }
    var controlsVisible by remember(videoState.callId) { mutableStateOf(true) }
    var controlsActivityId by remember(videoState.callId) { mutableIntStateOf(0) }
    var previewCorner by remember(videoState.callId, previewPreferences) {
        mutableStateOf(
            storedSelfPreviewCorner(previewPreferences.getString(SelfPreviewCornerKey, null)),
        )
    }
    var draggedPreviewPosition by remember(videoState.callId) { mutableStateOf<SelfPreviewPosition?>(null) }
    var previewDragging by remember(videoState.callId) { mutableStateOf(false) }
    var topControlsHeight by remember(videoState.callId) { mutableIntStateOf(0) }
    var bottomControlsHeight by remember(videoState.callId) { mutableIntStateOf(0) }
    val toggleControls = {
        controlsVisible = nextVideoControlsVisibility(
            currentVisible = controlsVisible,
            remoteVideoVisible = controlsMayAutoHide,
            event = VideoControlsVisibilityEvent.SurfaceTapped,
        )
    }
    val restartControlsAutoHide = {
        controlsActivityId += 1
    }

    LaunchedEffect(videoState.callId, videoState.remoteSending, remoteFrameVisible) {
        if (videoState.remoteSending && remoteFrameVisible) remoteVideoWasVisible = true
    }
    LaunchedEffect(videoState.callId, localSource, remoteSource, presentation.blockProximity) {
        onVideoVisibilityChanged(presentation.blockProximity)
    }
    LaunchedEffect(
        videoState.callId,
        controlsMayAutoHide,
        controlsVisible,
        previewDragging,
        controlsActivityId,
    ) {
        if (!controlsMayAutoHide) {
            controlsVisible = nextVideoControlsVisibility(
                currentVisible = controlsVisible,
                remoteVideoVisible = false,
                event = VideoControlsVisibilityEvent.VideoChanged,
            )
        } else if (controlsVisible && !previewDragging) {
            delay(VideoControlsAutoHideMillis)
            controlsVisible = nextVideoControlsVisibility(
                currentVisible = controlsVisible,
                remoteVideoVisible = true,
                event = VideoControlsVisibilityEvent.AutoHideElapsed,
            )
        }
    }
    DisposableEffect(videoState.callId) {
        onDispose { onVideoVisibilityChanged(false) }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(CallBackgroundTop, CallBackgroundBottom))),
    ) {
        val density = LocalDensity.current
        val layoutDirection = LocalLayoutDirection.current
        val safeDrawingInsets = WindowInsets.safeDrawing
        val controlHorizontalPadding = if (maxWidth.value < 360f) 8.dp else 12.dp
        val controlLayout = callControlLayout(
            videoAllowed = true,
            videoModeActive = true,
            widthDp = (maxWidth.value - controlHorizontalPadding.value * 2f).coerceAtLeast(0f),
            heightDp = maxHeight.value,
            fontScale = density.fontScale,
        )
        if (remoteSource != null) {
            BoxWithConstraints(Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center) {
                val frameSize = cameraVideoSize(maxWidth.value, maxHeight.value,
                    remoteFrameSize.first, remoteFrameSize.second)
                ScreenVideoRenderer(
                    source = remoteSource,
                    contentDescription = null,
                    keepLastFrame = false,
                    modifier = Modifier.size(frameSize.width.dp, frameSize.height.dp),
                    onFrameSizeChanged = { width, height -> remoteFrameSize = width to height },
                    onFrameVisibilityChanged = { remoteFrameVisible = it },
                )
            }
        }
        if (!presentation.remoteVideoVisible) {
            CallScreenSurface(
                status = status,
                peerName = peerName,
                contactAddress = contactAddress,
                fallbackLogin = fallbackLogin,
                detail = durationText,
                statusColor = statusColor,
                prominentAvatar = true,
            ) {}
        }

        if (controlsMayAutoHide) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(videoState.callId, controlsMayAutoHide, controlsVisible) {
                        detectTapGestures { toggleControls() }
                    },
            )
        }

        AnimatedVisibility(
            visible = showVideoRecoveryOverlay,
            modifier = Modifier.align(Alignment.Center),
            enter = fadeIn(tween(160)),
            exit = fadeOut(tween(240)),
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.Black.copy(alpha = 0.66f))
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    color = Color.White,
                    strokeWidth = 2.dp,
                )
                Text(
                    text = appString(R.string.text_restoring_video_169),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
            }
        }

        AnimatedVisibility(
            visible = controlsVisible && presentation.remoteVideoVisible,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .videoCallHeaderInsets(landscape)
                .onSizeChanged { size ->
                    if (size.height > 0) topControlsHeight = size.height
                },
            enter = fadeIn(tween(VideoControlsFadeInMillis)),
            exit = fadeOut(tween(VideoControlsFadeOutMillis)),
        ) {
            VideoCallHeader(status, peerName, durationText, statusColor)
        }

        if (
            localSource != null &&
            videoState.requested &&
            (landscape || bottomControlsHeight > 0) &&
            (topControlsHeight > 0 || !presentation.remoteVideoVisible)
        ) {
            val compactPreview = density.fontScale >= 1.3f || org.tinitalk.ui.compactLandscape()
            val previewSize = selfPreviewSize(
                compact = compactPreview,
                frameWidth = localFrameSize.first,
                frameHeight = localFrameSize.second,
                landscape = maxWidth > maxHeight,
                maxWidthDp = with(density) {
                    (maxWidth.toPx() - safeDrawingInsets.getLeft(density, layoutDirection) -
                        safeDrawingInsets.getRight(density, layoutDirection)).toDp().value
                } - SelfPreviewEdgeSpacing.value * 2 - if (landscape && controlsVisible) LandscapeCallControlsWidth.value else 0f,
                maxHeightDp = with(density) {
                    val top = if (controlsVisible) maxOf(safeDrawingInsets.getTop(density), topControlsHeight)
                        else safeDrawingInsets.getTop(density)
                    val bottom = if (controlsVisible && !landscape) maxOf(safeDrawingInsets.getBottom(density), bottomControlsHeight)
                        else safeDrawingInsets.getBottom(density)
                    (maxHeight.toPx() - top - bottom).toDp().value
                } - SelfPreviewEdgeSpacing.value * 2,
            )
            val previewWidth = with(density) { previewSize.widthDp.dp.toPx() }
            val previewHeight = with(density) { previewSize.heightDp.dp.toPx() }
            val previewBounds = selfPreviewBounds(
                containerWidth = with(density) { maxWidth.toPx() },
                containerHeight = with(density) { maxHeight.toPx() },
                previewWidth = previewWidth,
                previewHeight = previewHeight,
                safeLeft = safeDrawingInsets.getLeft(density, layoutDirection).toFloat(),
                safeTop = safeDrawingInsets.getTop(density).toFloat(),
                safeRight = safeDrawingInsets.getRight(density, layoutDirection).toFloat() +
                    if (landscape && controlsVisible) with(density) { LandscapeCallControlsWidth.toPx() } else 0f,
                safeBottom = safeDrawingInsets.getBottom(density).toFloat(),
                topControlsHeight = topControlsHeight.toFloat(),
                bottomControlsHeight = if (landscape) 0f else bottomControlsHeight.toFloat(),
                controlsVisible = controlsVisible,
                edgeSpacing = with(density) { SelfPreviewEdgeSpacing.toPx() },
            )
            val targetPreviewPosition = draggedPreviewPosition?.let { position ->
                clampSelfPreviewPosition(position, previewBounds)
            } ?: selfPreviewPosition(previewCorner, previewBounds)
            val animatedPreviewOffset by animateIntOffsetAsState(
                targetValue = IntOffset(
                    x = targetPreviewPosition.x.roundToInt(),
                    y = targetPreviewPosition.y.roundToInt(),
                ),
                animationSpec = if (draggedPreviewPosition == null) {
                    tween(VideoPreviewSnapMillis)
                } else {
                    snap()
                },
                label = "selfPreviewOffset",
            )
            val finishPreviewDrag = {
                draggedPreviewPosition?.let { position ->
                    val corner = nearestSelfPreviewCorner(position, previewBounds)
                    previewCorner = corner
                    previewPreferences.edit { putString(SelfPreviewCornerKey, corner.name) }
                }
                draggedPreviewPosition = null
                previewDragging = false
            }
            Box(
                modifier = Modifier
                    .offset { animatedPreviewOffset }
                    .size(
                        width = previewSize.widthDp.dp,
                        height = previewSize.heightDp.dp,
                    )
                    .clip(RectangleShape)
                    .background(Color(0xFF172438))
                    .border(1.dp, Color.White.copy(alpha = 0.34f), RectangleShape),
            ) {
                VideoCallRenderer(
                    source = localSource,
                    mirror = videoState.facing == CameraFacing.Front,
                    onFrameSizeChanged = { width, height -> localFrameSize = width to height },
                    localOverlay = true,
                    modifier = Modifier.fillMaxSize(),
                    onClick = toggleControls.takeIf { controlsMayAutoHide },
                    onDragStart = {
                        previewDragging = true
                        draggedPreviewPosition = clampSelfPreviewPosition(
                            SelfPreviewPosition(
                                x = animatedPreviewOffset.x.toFloat(),
                                y = animatedPreviewOffset.y.toFloat(),
                            ),
                            previewBounds,
                        )
                    },
                    onDrag = { dragX, dragY ->
                        val current = draggedPreviewPosition ?: SelfPreviewPosition(
                            x = animatedPreviewOffset.x.toFloat(),
                            y = animatedPreviewOffset.y.toFloat(),
                        )
                        draggedPreviewPosition = clampSelfPreviewPosition(
                            SelfPreviewPosition(
                                x = current.x + dragX,
                                y = current.y + dragY,
                            ),
                            previewBounds,
                        )
                    },
                    onDragEnd = finishPreviewDrag,
                    contentDescription = if (controlsMayAutoHide) {
                        if (controlsVisible) appString(R.string.text_hide_controls_170) else appString(R.string.text_show_controls_171)
                    } else null,
                    onFrameVisibilityChanged = { localFrameVisible = it },
                )
            }
        }

        if (landscape) {
            val videoNotice = weakNetworkVideoMessage(videoState.allowed, videoState.requested, videoState.networkGated)
                ?: if (videoState.failure != null && !videoState.sending) {
                    appString(R.string.text_could_not_turn_on_the_camera_172)
                } else null
            if (videoNotice != null) {
                Box(Modifier.align(Alignment.BottomCenter).safeDrawingPadding()
                    .padding(end = LandscapeCallControlsWidth, bottom = 8.dp)) {
                    Text(videoNotice, Modifier.background(CallBackgroundTop.copy(alpha = 0.9f))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                        color = Color(0xFFFFCA6A), style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center)
                }
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
                AnimatedVisibility(visible = controlsVisible,
                    enter = slideInHorizontally(tween(VideoControlsSlideMillis)) { it },
                    exit = slideOutHorizontally(tween(VideoControlsSlideMillis)) { it }) {
                    LandscapeCallControls(muted, currentEndpoint, availableEndpoints,
                        cameraVisible = true, cameraEnabled = !videoState.screen.requested,
                        backgroundColor = CallControlsOverlayBackground,
                        switchCameraVisible = true,
                        switchCameraEnabled = presentation.switchCameraEnabled,
                        onSwitchCamera = { restartControlsAutoHide(); onSwitchCamera() },
                        cameraRequested = videoState.requested,
                        onMute = { restartControlsAutoHide(); onMute(it) },
                        onSelectEndpoint = { restartControlsAutoHide(); onSelectEndpoint(it) },
                        onShowRoutePicker = { restartControlsAutoHide(); onShowRoutePicker() },
                        onCamera = { restartControlsAutoHide(); onCamera(it) }, onEnd = onEnd)
                }
            }
        } else AnimatedVisibility(
            visible = controlsVisible,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .onSizeChanged { size ->
                    if (size.height > 0) bottomControlsHeight = size.height
                },
            enter = slideInVertically(
                animationSpec = tween(VideoControlsSlideMillis),
                initialOffsetY = { it },
            ) + fadeIn(tween(VideoControlsFadeInMillis)),
            exit = slideOutVertically(
                animationSpec = tween(VideoControlsSlideMillis),
                targetOffsetY = { it },
            ) + fadeOut(tween(VideoControlsFadeOutMillis)),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CallControlsOverlayBackground)
                    .navigationBarsPadding()
                    .then(
                        if (controlLayout.scrollable) {
                            Modifier
                                .heightIn(max = controlLayout.viewportHeightDp.dp)
                                .verticalScroll(rememberScrollState())
                        } else {
                            Modifier
                        },
                    )
                    .padding(horizontal = controlHorizontalPadding, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                weakNetworkVideoMessage(
                    videoAllowed = videoState.allowed,
                    cameraRequested = videoState.requested,
                    networkGated = videoState.networkGated,
                )?.let { message ->
                    Text(
                        text = message,
                        color = Color(0xFFFFCA6A),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(6.dp))
                }
                if (videoState.failure != null && !videoState.sending) {
                    Text(
                        text = appString(R.string.text_could_not_turn_on_the_camera_172),
                        color = Color(0xFFFFCA6A),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(6.dp))
                }
                Text(
                    text = appString(R.string.text_audio_value_166, audioEndpointLabel(currentEndpoint)),
                    color = Color.White.copy(alpha = 0.72f),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                AdaptiveVideoControls(
                    muted = muted,
                    currentEndpoint = currentEndpoint,
                    availableEndpoints = availableEndpoints,
                    layout = controlLayout,
                    cameraRequested = videoState.requested,
                    switchCameraEnabled = presentation.switchCameraEnabled,
                    cameraEnabled = !videoState.screen.requested,
                    onMute = { muted ->
                        restartControlsAutoHide()
                        onMute(muted)
                    },
                    onSelectEndpoint = { endpoint ->
                        restartControlsAutoHide()
                        onSelectEndpoint(endpoint)
                    },
                    onShowRoutePicker = {
                        restartControlsAutoHide()
                        onShowRoutePicker()
                    },
                    onSwitchCamera = {
                        restartControlsAutoHide()
                        onSwitchCamera()
                    },
                    onCamera = { requested ->
                        restartControlsAutoHide()
                        onCamera(requested)
                    },
                    onEnd = {
                        restartControlsAutoHide()
                        onEnd()
                    },
                )
            }
        }
    }
}

private const val VideoControlsAutoHideMillis = 5_000L
private const val VideoControlsFadeInMillis = 180
private const val VideoControlsFadeOutMillis = 220
private const val VideoControlsSlideMillis = 260
private const val VideoPreviewSnapMillis = 220
private const val SecurityPanelReservedHeightDp = 72f
private const val ShortAudioScreenHeightDp = 640f
private val ShortAudioScreenAvatarSize = 120.dp
private const val SelfPreviewPreferencesName = "call_ui"
private const val SelfPreviewCornerKey = "self_preview_corner"
private val SelfPreviewEdgeSpacing = 12.dp

@Composable
private fun VideoFallbackContent(
    peerName: String,
    contactAddress: ContactAddress? = null,
    fallbackLogin: String = peerName,
    avatarSize: Dp = 120.dp,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        ContactAvatar(
            address = contactAddress,
            displayName = peerName,
            fallbackLogin = fallbackLogin,
            size = avatarSize,
            modifier = Modifier.testTag("call-peer-avatar"),
            borderWidth = 0.dp,
        )
    }
}

@Composable
internal fun AdaptiveAudioControls(
    muted: Boolean,
    currentEndpoint: AudioEndpoint?,
    availableEndpoints: List<AudioEndpoint>,
    layout: CallControlLayout,
    videoAllowed: Boolean,
    cameraRequested: Boolean,
    onMute: (Boolean) -> Unit,
    onSelectEndpoint: (AudioEndpoint) -> Unit,
    onShowRoutePicker: () -> Unit,
    onCamera: (Boolean) -> Unit,
    onEnd: () -> Unit,
) {
    val compact = layout.columns == 2
    val cameraActionVisible = CallControlAction.Camera in layout.actions
    if (cameraActionVisible && !compact) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            CameraCallAction(cameraRequested, Modifier.weight(1f), onCamera, enabled = videoAllowed)
            AudioRouteAction(
                currentEndpoint,
                availableEndpoints,
                Modifier.weight(1f),
                onSelectEndpoint,
                onShowRoutePicker,
                compact = true,
            )
            MuteCallAction(muted, Modifier.weight(1f), onMute, compact = true)
            EndCallAction(Modifier.weight(1f), onEnd, compact = true)
        }
    } else if (cameraActionVisible) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                CameraCallAction(cameraRequested, Modifier.weight(1f), onCamera, enabled = videoAllowed)
                AudioRouteAction(
                    currentEndpoint,
                    availableEndpoints,
                    Modifier.weight(1f),
                    onSelectEndpoint,
                    onShowRoutePicker,
                    compact = true,
                )
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                MuteCallAction(muted, Modifier.weight(1f), onMute, compact = true)
                EndCallAction(Modifier.weight(1f), onEnd, compact = true)
            }
        }
    } else if (!compact) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            AudioRouteAction(
                currentEndpoint,
                availableEndpoints,
                Modifier.weight(1f),
                onSelectEndpoint,
                onShowRoutePicker,
            )
            MuteCallAction(muted, Modifier.weight(1f), onMute)
            EndCallAction(Modifier.weight(1f), onEnd)
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                AudioRouteAction(
                    currentEndpoint,
                    availableEndpoints,
                    Modifier.weight(1f),
                    onSelectEndpoint,
                    onShowRoutePicker,
                    compact = true,
                )
                MuteCallAction(muted, Modifier.weight(1f), onMute, compact = true)
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                Spacer(Modifier.weight(1f))
                EndCallAction(Modifier.weight(1f), onEnd, compact = true)
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun AdaptiveVideoControls(
    muted: Boolean,
    currentEndpoint: AudioEndpoint?,
    availableEndpoints: List<AudioEndpoint>,
    layout: CallControlLayout,
    cameraRequested: Boolean,
    switchCameraEnabled: Boolean,
    cameraEnabled: Boolean,
    onMute: (Boolean) -> Unit,
    onSelectEndpoint: (AudioEndpoint) -> Unit,
    onShowRoutePicker: () -> Unit,
    onSwitchCamera: () -> Unit,
    onCamera: (Boolean) -> Unit,
    onEnd: () -> Unit,
) {
    val buttonSize = layout.buttonSizeDp.dp
    Row(modifier = Modifier.fillMaxWidth()) {
        SwitchCameraCallAction(switchCameraEnabled, Modifier.weight(1f), onSwitchCamera, buttonSize)
        CameraCallAction(cameraRequested, Modifier.weight(1f), onCamera, buttonSize, cameraEnabled)
        AudioRouteAction(
            currentEndpoint = currentEndpoint,
            availableEndpoints = availableEndpoints,
            modifier = Modifier.weight(1f),
            onSelectEndpoint = onSelectEndpoint,
            onShowPicker = onShowRoutePicker,
            compact = true,
            buttonSize = buttonSize,
        )
        MuteCallAction(muted, Modifier.weight(1f), onMute, compact = true, buttonSize = buttonSize)
        EndCallAction(Modifier.weight(1f), onEnd, compact = true, buttonSize = buttonSize)
    }
}

@Composable
internal fun SwitchCameraCallAction(
    enabled: Boolean,
    modifier: Modifier,
    onSwitchCamera: () -> Unit,
    buttonSize: Dp = CompactCallActionSizeDp.dp,
) {
    RoundCallAction(
        label = appString(R.string.text_rotate_173),
        modifier = modifier,
        contentDescription = appString(R.string.text_switch_camera_174),
        color = Color(0xFF33465F),
        enabled = enabled,
        onClick = onSwitchCamera,
        iconResource = R.drawable.ic_camera_switch,
        buttonSize = buttonSize,
        labelMaxLines = 1,
    )
}

@Composable
internal fun CameraCallAction(
    requested: Boolean,
    modifier: Modifier,
    onCamera: (Boolean) -> Unit,
    buttonSize: Dp = CompactCallActionSizeDp.dp,
    enabled: Boolean = true,
) {
    RoundCallAction(
        label = appString(R.string.text_camera_175),
        modifier = modifier,
        enabled = enabled,
        contentDescription = if (requested) appString(R.string.text_turn_camera_off_176) else appString(R.string.text_turn_camera_on_177),
        color = if (requested) Color(0xFF2A8C76) else Color(0xFF33465F),
        onClick = { onCamera(!requested) },
        iconResource = R.drawable.ic_videocam,
        buttonSize = buttonSize,
        labelMaxLines = 1,
    )
}

@Composable
internal fun EndCallAction(
    modifier: Modifier,
    onEnd: () -> Unit,
    compact: Boolean = false,
    buttonSize: Dp = if (compact) CompactCallActionSizeDp.dp else 72.dp,
) {
    RoundCallAction(
        label = appString(R.string.text_end_call_98),
        modifier = modifier,
        color = CallRejectRed,
        onClick = onEnd,
        iconRotation = 135f,
        buttonSize = buttonSize,
        labelMaxLines = 1,
    )
}

@Composable
internal fun MuteCallAction(
    muted: Boolean,
    modifier: Modifier = Modifier,
    onMute: (Boolean) -> Unit,
    compact: Boolean = false,
    buttonSize: Dp = if (compact) CompactCallActionSizeDp.dp else 72.dp,
) {
    RoundCallAction(
        label = appString(R.string.text_microphone_178),
        modifier = modifier,
        contentDescription = if (muted) appString(R.string.text_unmute_microphone_179) else appString(R.string.text_mute_microphone_180),
        color = if (muted) Color(0xFF315EA8) else Color(0xFF33465F),
        onClick = { onMute(!muted) },
        iconResource = R.drawable.ic_mic_off,
        buttonSize = buttonSize,
        labelMaxLines = 1,
    )
}

@Composable
internal fun AudioRouteAction(
    currentEndpoint: AudioEndpoint?,
    availableEndpoints: List<AudioEndpoint>,
    modifier: Modifier = Modifier,
    onSelectEndpoint: (AudioEndpoint) -> Unit,
    onShowPicker: () -> Unit,
    compact: Boolean = false,
    buttonSize: Dp = if (compact) CompactCallActionSizeDp.dp else 72.dp,
) {
    val directRoute = directAudioRoute(currentEndpoint, availableEndpoints)
    RoundCallAction(
        label = appString(R.string.text_audio_181),
        modifier = modifier,
        contentDescription = when (directRoute?.type) {
            CallEndpointCompat.TYPE_SPEAKER -> appString(R.string.text_turn_speakerphone_on_182)
            CallEndpointCompat.TYPE_EARPIECE -> appString(R.string.text_turn_speakerphone_off_183)
            else -> appString(R.string.text_choose_audio_device_current_value_184, audioEndpointLabel(currentEndpoint))
        },
        color = Color(0xFF33465F),
        enabled = availableEndpoints.isNotEmpty(),
        onClick = {
            if (directRoute != null) {
                onSelectEndpoint(directRoute)
            } else {
                onShowPicker()
            }
        },
        iconResource = audioEndpointIcon(currentEndpoint),
        buttonSize = buttonSize,
        labelMaxLines = 1,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AudioRoutePicker(
    visible: Boolean,
    currentEndpoint: AudioEndpoint?,
    availableEndpoints: List<AudioEndpoint>,
    onDismiss: () -> Unit,
    onSelectEndpoint: (AudioEndpoint) -> Unit,
) {
    if (!visible) return
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
        Text(
            text = appString(R.string.text_audio_output_185),
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        availableEndpoints.forEach { endpoint ->
            val selected = endpoint.id == currentEndpoint?.id
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        onDismiss()
                        onSelectEndpoint(endpoint)
                    }
                    .padding(PaddingValues(horizontal = 24.dp, vertical = 16.dp)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(audioEndpointIcon(endpoint)),
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                )
                Column(modifier = Modifier.weight(1f).padding(start = 18.dp)) {
                    Text(audioEndpointLabel(endpoint), style = MaterialTheme.typography.titleMedium)
                    if (selected) {
                        Text(
                            text = appString(R.string.text_currently_in_use_186),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                if (selected) {
                    Text("✓", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.navigationBarsPadding().height(12.dp))
        }
    }
}

internal fun directAudioRoute(current: AudioEndpoint?, available: List<AudioEndpoint>): AudioEndpoint? {
    val phoneRouteTypes = setOf(CallEndpointCompat.TYPE_EARPIECE, CallEndpointCompat.TYPE_SPEAKER)
    if (available.size != 2 || available.map { it.type }.toSet() != phoneRouteTypes) return null
    val nextType = if (current?.type == CallEndpointCompat.TYPE_SPEAKER) {
        CallEndpointCompat.TYPE_EARPIECE
    } else {
        CallEndpointCompat.TYPE_SPEAKER
    }
    return available.firstOrNull { it.type == nextType }
}

internal fun speakerRouteOnCameraPress(
    cameraRequested: Boolean,
    current: AudioEndpoint?,
    available: List<AudioEndpoint>,
): AudioEndpoint? = if (cameraRequested && current?.type == CallEndpointCompat.TYPE_EARPIECE) {
    available.firstOrNull { it.type == CallEndpointCompat.TYPE_SPEAKER }
} else {
    null
}

private fun audioEndpointLabel(endpoint: AudioEndpoint?): String = when (endpoint?.type) {
    CallEndpointCompat.TYPE_EARPIECE -> appString(R.string.text_phone_187)
    CallEndpointCompat.TYPE_SPEAKER -> appString(R.string.text_speaker_188)
    CallEndpointCompat.TYPE_BLUETOOTH -> "Bluetooth"
    CallEndpointCompat.TYPE_WIRED_HEADSET -> appString(R.string.text_headphones_189)
    CallEndpointCompat.TYPE_STREAMING -> appString(R.string.text_other_device_190)
    else -> appString(R.string.text_device_191)
}

private fun audioEndpointIcon(endpoint: AudioEndpoint?): Int = when (endpoint?.type) {
    CallEndpointCompat.TYPE_BLUETOOTH -> R.drawable.ic_bluetooth
    CallEndpointCompat.TYPE_WIRED_HEADSET -> R.drawable.ic_headset
    CallEndpointCompat.TYPE_SPEAKER -> R.drawable.ic_volume_up
    CallEndpointCompat.TYPE_EARPIECE -> R.drawable.ic_phone_in_talk
    else -> R.drawable.ic_call
}
