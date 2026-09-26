package org.tinitalk.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-port-mdpi")
class WindowLayoutTest {
    @get:Rule val composeRule = createEmptyComposeRule()

    @Test fun windowBoundsDriveLayoutIndependentlyOfConfigurationAndFontScale() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        var size by mutableStateOf(IntSize(1920, 1200))
        val window = object : WindowInfo {
            override val isWindowFocused = true
            override val containerSize get() = size
        }
        var result = false to false
        composeRule.runOnUiThread {
            activity.get().setContent {
                CompositionLocalProvider(
                    LocalWindowInfo provides window,
                    LocalDensity provides Density(1.5f, fontScale = 1.4f),
                ) {
                    result = landscapeLayout() to compactLandscape()
                }
            }
        }
        try {
            composeRule.runOnIdle { assertEquals(true to false, result) }
            // Phone landscape: the same density, but a short window.
            composeRule.runOnIdle { size = IntSize(1280, 576) }
            composeRule.runOnIdle { assertEquals(true to true, result) }
            // Rotation / multi-window resizing updates the layout without Activity recreation.
            composeRule.runOnIdle { size = IntSize(576, 1280) }
            composeRule.runOnIdle { assertEquals(false to false, result) }
            composeRule.runOnIdle { size = IntSize(750, 450) }
            composeRule.runOnIdle { assertEquals(false to false, result) }
        } finally {
            activity.pause().stop().destroy()
        }
    }
}
