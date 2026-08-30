package org.tinitalk.media

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ManifestPermissionTest {
    @Test
    fun appDeclaresRequiredPermissionsAndFirebaseInstallationIdMetadata() {
        val manifest = File("src/main/AndroidManifest.xml").readText()

        assertTrue(manifest.contains("android.permission.CAMERA"))
        assertTrue(manifest.contains("android.permission.FOREGROUND_SERVICE_CAMERA"))
        assertTrue(
            manifest.contains(
                "android:name=\"firebase_messaging_installation_id_enabled\" android:value=\"true\"",
            ),
        )
    }
}
