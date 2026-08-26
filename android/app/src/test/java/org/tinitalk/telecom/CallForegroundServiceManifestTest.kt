package org.tinitalk.telecom

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CallForegroundServiceManifestTest {
    @Test
    fun manifestDeclaresAudioCallPermissionsAndPhoneCallService() {
        val manifest = File("src/main/AndroidManifest.xml").readText()

        assertTrue(manifest.contains("android.permission.RECORD_AUDIO"))
        assertTrue(manifest.contains("android.permission.MANAGE_OWN_CALLS"))
        assertTrue(manifest.contains("android.permission.WAKE_LOCK"))
        assertTrue(manifest.contains("android.permission.FOREGROUND_SERVICE_PHONE_CALL"))
        assertTrue(manifest.contains("android:foregroundServiceType=\"phoneCall\""))
    }
}
