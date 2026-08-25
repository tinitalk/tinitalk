package org.tinitalk.media

import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class ManifestPermissionTest {
    @Test
    fun appDoesNotRequestCameraPermission() {
        val manifest = File("src/main/AndroidManifest.xml").readText()

        assertFalse(manifest.contains("android.permission.CAMERA"))
    }
}
