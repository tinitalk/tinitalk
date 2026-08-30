package org.tinitalk.push

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Test

class FirebaseBuildContractTest {
    @Test
    fun trackedGradleSourcesDoNotWireGoogleServicesPlugin() {
        val appDirectory = generateSequence(File(".").canonicalFile) { it.parentFile }
            .firstOrNull { File(it, "src/main/AndroidManifest.xml").isFile }
            ?: error("Could not locate the Android app directory from the Gradle test working directory")
        val androidDirectory = appDirectory.parentFile

        val appBuildScript = File(appDirectory, "build.gradle.kts").readText()
        val rootBuildScript = File(androidDirectory, "build.gradle.kts").readText()
        val versionCatalog = File(androidDirectory, "gradle/libs.versions.toml").readText()

        assertFalse(appBuildScript.contains("com.google.gms.google-services"))
        assertFalse(rootBuildScript.contains("libs.plugins.google.services"))
        assertFalse(versionCatalog.contains("googleServices"))
        assertFalse(versionCatalog.contains("google-services"))
    }
}
