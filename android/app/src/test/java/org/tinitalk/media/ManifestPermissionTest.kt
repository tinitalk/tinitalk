package org.tinitalk.media

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class ManifestPermissionTest {
    @Test
    fun appDeclaresRequiredPermissionsAndUnifiedPushService() {
        val manifest = File("src/main/AndroidManifest.xml").readText()

        assertTrue(manifest.contains("android.permission.CAMERA"))
        assertTrue(manifest.contains("android.permission.FOREGROUND_SERVICE_CAMERA"))
        assertTrue(
            manifest.contains(
                "android:name=\".push.TinitalkPushService\"",
            ),
        )
        assertFalse(manifest.contains("android.permission.READ_MEDIA_IMAGES"))
        assertFalse(manifest.contains("android.permission.READ_EXTERNAL_STORAGE"))
    }
}
