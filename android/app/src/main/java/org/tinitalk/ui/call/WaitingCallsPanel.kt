package org.tinitalk.ui.call

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.tinitalk.R
import org.tinitalk.call.AccountCallOwner
import org.tinitalk.call.WaitingCalls
import org.tinitalk.call.WaitingCallsState
import org.tinitalk.data.ContactAddress
import org.tinitalk.ui.ContactAvatar
import org.tinitalk.ui.theme.BrandGold

@Composable
internal fun WaitingCallsPanel(modifier: Modifier = Modifier) {
    var state by remember { mutableStateOf(WaitingCalls.snapshot()) }
    DisposableEffect(Unit) {
        val observer: (WaitingCallsState) -> Unit = { state = it }
        WaitingCalls.observe(observer)
        onDispose { WaitingCalls.removeObserver(observer) }
    }
    WaitingCallsPanel(state, onAnswer = WaitingCalls::answer, onReject = WaitingCalls::reject, modifier = modifier)
}

@Composable
internal fun WaitingCallsPanel(
    state: WaitingCallsState,
    onAnswer: (AccountCallOwner) -> Unit,
    onReject: (AccountCallOwner) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.calls.isEmpty()) return
    BoxWithConstraints(modifier.fillMaxWidth()) {
        // Grow with the callers instead of clipping the second row at a fixed height.
        // Keep part of the ongoing call accessible; longer lists can still scroll.
        Surface(
            modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight * 0.65f),
            shape = MaterialTheme.shapes.large,
            color = Color(0xFF36383C),
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 0.dp,
        ) {
            Column(
                Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.waiting_calls_title), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.waiting_answer_ends_current),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.72f),
                    )
                }
                state.calls.forEachIndexed { index, pending ->
                    key(pending.invite.owner) {
                        if (index > 0) HorizontalDivider(color = Color.White.copy(alpha = 0.12f))
                        val invite = pending.invite
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                ContactAvatar(
                                    address = invite.callerLogin?.takeIf { it.isNotBlank() }
                                        ?.let { ContactAddress.of(invite.sessionBinding.serverUrl, it) },
                                    displayName = invite.caller,
                                    fallbackLogin = invite.callerLogin,
                                    size = 40.dp,
                                    borderWidth = 0.dp,
                                )
                                Text(
                                    invite.caller,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Row(
                                Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                CompositionLocalProvider(LocalRippleConfiguration provides null) {
                                    Button(
                                        onClick = { onReject(invite.owner) },
                                        enabled = state.answering == null,
                                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).fillMaxHeight(),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = Color(0xFF4A4C50),
                                            contentColor = MaterialTheme.colorScheme.onSurface,
                                        ),
                                    ) {
                                        Text(stringResource(R.string.text_busy_91), textAlign = TextAlign.Center)
                                    }
                                }
                                Button(
                                    onClick = { onAnswer(invite.owner) },
                                    enabled = state.answering == null && pending.acknowledged,
                                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).fillMaxHeight(),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = BrandGold,
                                        contentColor = MaterialTheme.colorScheme.onPrimary,
                                    ),
                                ) {
                                    Text(stringResource(R.string.text_answer_64), textAlign = TextAlign.Center)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
