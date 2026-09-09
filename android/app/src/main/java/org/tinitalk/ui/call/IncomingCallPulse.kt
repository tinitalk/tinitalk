package org.tinitalk.ui.call

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** One clock for both buttons, disposed while the screen or reply panel hides them. */
@Composable
internal fun rememberIncomingCallPulse(enabled: Boolean): State<Float> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember(lifecycle) {
        mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    if (!enabled || !resumed) return rememberUpdatedState(1f)

    val transition = rememberInfiniteTransition(label = "incomingCallPulse")
    return transition.animateFloat(
        initialValue = 0f,
        // Drawing clamps at 1: 1600 ms expansion followed by 300 ms invisible.
        // With system animations disabled, the terminal value is also invisible.
        targetValue = 1900f / 1600f,
        animationSpec = infiniteRepeatable(tween(1900, easing = LinearEasing)),
        label = "incomingCallPulseProgress",
    )
}
