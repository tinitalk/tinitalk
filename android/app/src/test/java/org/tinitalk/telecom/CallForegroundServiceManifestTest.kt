package org.tinitalk.telecom

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import org.tinitalk.call.CallUiStateStore
import org.tinitalk.push.IncomingCallForegroundService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CallForegroundServiceManifestTest {
    @Test
    fun activeCallDeclaresAndUsesMicrophoneForegroundType() {
        val context = RuntimeEnvironment.getApplication()
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        )
        assertTrue(packageInfo.requestedPermissions.orEmpty().contains(Manifest.permission.RECORD_AUDIO))
        assertTrue(packageInfo.requestedPermissions.orEmpty().contains(Manifest.permission.FOREGROUND_SERVICE_MICROPHONE))
        assertTrue(packageInfo.requestedPermissions.orEmpty().contains(Manifest.permission.CAMERA))
        assertTrue(packageInfo.requestedPermissions.orEmpty().contains(Manifest.permission.FOREGROUND_SERVICE_CAMERA))
        assertTrue(packageInfo.requestedPermissions.orEmpty().contains(Manifest.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION))

        val activeCallTypes = serviceInfo(context, CallForegroundService::class.java).foregroundServiceType
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            activeCallTypes,
        )

        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            callForegroundServiceType(cameraSending = false),
        )
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
            callForegroundServiceType(cameraSending = true),
        )
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            callForegroundServiceType(cameraSending = false, screenSending = true),
        )
    }

    @Test
    @Config(sdk = [29, 35])
    fun screenForegroundTypeSurvivesNotificationAndCameraUpdates() {
        CallUiStateStore.reset()
        val application = RuntimeEnvironment.getApplication()
        Shadows.shadowOf(application).grantPermissions("${application.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        val controller = Robolectric.buildService(CallForegroundService::class.java).create()
        val service = controller.get()
        val update = CallForegroundService::class.java.getDeclaredMethod(
            "updateForegroundType", Boolean::class.javaObjectType, Boolean::class.javaObjectType,
        ).apply { isAccessible = true }
        try {
            assertTrue(update.invoke(service, false, null) as Boolean)
            assertTrue(update.invoke(service, false, true) as Boolean)
            val screenTypes = callForegroundServiceType(cameraSending = false, screenSending = true)
            assertEquals(screenTypes, service.foregroundServiceType)

            assertTrue(update.invoke(service, null, null) as Boolean)
            assertTrue(update.invoke(service, false, null) as Boolean)
            assertEquals(screenTypes, service.foregroundServiceType)

            assertTrue(update.invoke(service, null, false) as Boolean)
            assertEquals(callForegroundServiceType(cameraSending = false), service.foregroundServiceType)
        } finally {
            controller.destroy()
            CallUiStateStore.reset()
        }
    }

    @Test
    fun ringingServiceDoesNotRequestMicrophoneAccess() {
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL,
            serviceInfo(RuntimeEnvironment.getApplication(), IncomingCallForegroundService::class.java).foregroundServiceType,
        )
    }

    private fun serviceInfo(context: Context, service: Class<*>) =
        context.packageManager.getServiceInfo(ComponentName(context, service), PackageManager.GET_META_DATA)
}
