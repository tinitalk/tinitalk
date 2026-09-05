buildscript {
    dependencies {
        // Keep AGP's built-in Kotlin compiler aligned with the Compose compiler plugin.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.compiler) apply false
}
