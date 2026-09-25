package org.tinitalk.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.tinitalk.ui.theme.TiniTalkTheme

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class SideNavigationBackdropTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun onlyLeftSystemNavigationGetsALighterBackgroundWithoutChangingLayout() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val insets = mutableStateOf<WindowInsets>(WindowInsets(left = 20))
        var panel = Color.Unspecified
        var navigation = Color.Unspecified
        compose.runOnUiThread {
            activity.get().setContent {
                TiniTalkTheme(darkTheme = true) {
                    panel = MaterialTheme.colorScheme.surface
                    navigation = MaterialTheme.colorScheme.surfaceVariant
                    Box(Modifier.size(100.dp).testTag("panel")
                        .background(panel).leftNavigationBarBackdrop(insets.value))
                }
            }
        }
        try {
            val node = compose.onNodeWithTag("panel")
            val bounds = node.fetchSemanticsNode().boundsInRoot
            for ((value, leftColor) in listOf(
                WindowInsets(left = 20) to navigation,
                WindowInsets(right = 20) to panel,
                WindowInsets(bottom = 20) to panel,
                WindowInsets(0) to panel,
                WindowInsets(left = 20) to navigation,
            )) {
                compose.runOnIdle { insets.value = value }
                assertEquals(bounds, node.fetchSemanticsNode().boundsInRoot)
                val image = node.captureToImage().toPixelMap()
                assertEquals(leftColor.toArgb(), image[5, image.height / 2].toArgb())
                assertEquals(panel.toArgb(), image[25, image.height / 2].toArgb())
                assertEquals(panel.toArgb(), image[image.width - 5, image.height / 2].toArgb())
            }
        } finally {
            activity.pause().stop().destroy()
        }
    }
}
