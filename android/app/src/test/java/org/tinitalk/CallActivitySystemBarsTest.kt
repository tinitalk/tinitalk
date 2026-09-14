package org.tinitalk

import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CallActivitySystemBarsTest {
    @Test
    fun navigationStaysTransparentAfterWindowDecorIsCreated() {
        val activity = Robolectric.buildActivity(CallActivity::class.java).create()
        try {
            val window = activity.get().window
            window.decorView // Theme attributes have now been applied to the window.
            assertFalse(window.isNavigationBarContrastEnforced)
            @Suppress("DEPRECATION")
            assertEquals(Color.TRANSPARENT, window.navigationBarColor)
            @Suppress("DEPRECATION")
            assertEquals(Color.TRANSPARENT, window.navigationBarDividerColor)
        } finally {
            activity.destroy()
        }
    }
}
