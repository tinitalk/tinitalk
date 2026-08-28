package org.tinitalk.telecom

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import org.tinitalk.push.IncomingCallForegroundService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CallForegroundServiceManifestTest {
    @Test
    fun activeCallDeclaresAndStartsWithMicrophoneForegroundType() {
        val context = RuntimeEnvironment.getApplication()
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        )
        assertTrue(packageInfo.requestedPermissions.orEmpty().contains(Manifest.permission.RECORD_AUDIO))
        assertTrue(packageInfo.requestedPermissions.orEmpty().contains(Manifest.permission.FOREGROUND_SERVICE_MICROPHONE))
        assertTrue(packageInfo.requestedPermissions.orEmpty().contains(Manifest.permission.CAMERA))
        assertTrue(packageInfo.requestedPermissions.orEmpty().contains(Manifest.permission.FOREGROUND_SERVICE_CAMERA))

        val activeCallTypes = serviceInfo(context, CallForegroundService::class.java).foregroundServiceType
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
            activeCallTypes,
        )

        Shadows.shadowOf(context).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val service = Robolectric.buildService(CallForegroundService::class.java).create().get()
        service.onStartCommand(null, 0, 1)
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            service.foregroundServiceType,
        )
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
            callForegroundServiceType(cameraSending = true),
        )
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
