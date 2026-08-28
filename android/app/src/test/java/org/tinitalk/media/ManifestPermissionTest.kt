package org.tinitalk.media

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ManifestPermissionTest {
    @Test
    fun appDeclaresCameraAndCameraForegroundServicePermissions() {
        val manifest = File("src/main/AndroidManifest.xml").readText()

        assertTrue(manifest.contains("android.permission.CAMERA"))
        assertTrue(manifest.contains("android.permission.FOREGROUND_SERVICE_CAMERA"))
    }
}
