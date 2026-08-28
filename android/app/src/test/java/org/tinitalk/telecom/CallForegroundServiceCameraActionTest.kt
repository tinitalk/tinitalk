package org.tinitalk.telecom

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CallForegroundServiceCameraActionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun cameraRequestIntentRoutesTheCallIdAndRequestedState() {
        val action = cameraCallAction(
            CallForegroundService.cameraRequestIntent(context, CurrentCall, requested = true),
        )

        assertEquals(CameraCallAction.Request(CurrentCall, requested = true), action)
    }

    @Test
    fun cameraLifecycleIntentRoutesForegroundAndPermissionSeparately() {
        val action = cameraCallAction(
            CallForegroundService.cameraForegroundIntent(
                context,
                CurrentCall,
                foreground = false,
                permissionGranted = true,
            ),
        )

        assertEquals(
            CameraCallAction.Foreground(CurrentCall, foreground = false, permissionGranted = true),
            action,
        )
    }

    @Test
    fun cameraSwitchIntentRemainsScopedToItsCall() {
        val action = cameraCallAction(CallForegroundService.cameraSwitchIntent(context, CurrentCall))

        assertEquals(CameraCallAction.Switch(CurrentCall), action)
    }

    private companion object {
        const val CurrentCall = "call-1"
    }
}
