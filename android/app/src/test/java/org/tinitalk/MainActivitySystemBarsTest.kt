package org.tinitalk

import android.graphics.Color
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainActivitySystemBarsTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun navigationRemainsTransparentAfterThemeAndContentAreInstalled() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            val window = activity.get().window
            window.decorView
            assertFalse("The system must not darken the app's panel background", window.isNavigationBarContrastEnforced)
            @Suppress("DEPRECATION")
            assertEquals(Color.TRANSPARENT, window.navigationBarColor)
            @Suppress("DEPRECATION")
            assertEquals(Color.TRANSPARENT, window.navigationBarDividerColor)
        } finally {
            activity.destroy()
        }
    }
}
