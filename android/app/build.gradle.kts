import java.util.Properties
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

val tinitalkAbi = providers.gradleProperty("tinitalkAbi").getOrElse("all")
require(tinitalkAbi == "arm64" || tinitalkAbi == "all") {
    "tinitalkAbi must be 'arm64' or 'all'"
}
val tinitalkVersionName = "0.12.0"

val releaseSigningPropertiesFile = rootProject.file("keystore/release.properties")
val releaseSigningProperties = Properties().apply {
    if (releaseSigningPropertiesFile.isFile) {
        releaseSigningPropertiesFile.inputStream().use(::load)
    }
}

fun releaseSigningProperty(name: String): String =
    requireNotNull(releaseSigningProperties.getProperty(name)?.takeIf(String::isNotBlank)) {
        "Missing '$name' in ${releaseSigningPropertiesFile.path}"
    }

val repositoryDir = rootDir.parentFile
val commitHash = runCatching {
    val process = ProcessBuilder(
        "git",
        "-c",
        "safe.directory=${repositoryDir.absolutePath.replace('\\', '/')}",
        "rev-parse",
        "--short=8",
        "HEAD",
    ).directory(repositoryDir).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().use { it.readText().trim() }
    check(process.waitFor() == 0 && output.matches(Regex("[0-9a-fA-F]+")))
    output
}.getOrDefault("unknown")

android {
    namespace = "org.tinitalk"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.tinitalk"
        minSdk = 26
        // API 37 requires a separate LAN-permission and certificate-transparency migration.
        //noinspection OldTargetApi
        targetSdk = 36
        versionCode = 16
        versionName = tinitalkVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("boolean", "FORCE_RELAY", providers.gradleProperty("tinitalkForceRelay").getOrElse("false"))
        buildConfigField("String", "COMMIT_HASH", "\"$commitHash\"")
        if (tinitalkAbi == "arm64") {
            // This opt-in compact APK is for ARM64 phones; the default APK keeps all ABIs.
            //noinspection ChromeOsAbiSupport
            ndk {
                abiFilters += "arm64-v8a"
            }
        }
    }

    signingConfigs {
        create("release") {
            if (releaseSigningPropertiesFile.isFile) {
                storeFile = rootProject.file(releaseSigningProperty("storeFile"))
                storePassword = releaseSigningProperty("storePassword")
                keyAlias = releaseSigningProperty("keyAlias")
                keyPassword = releaseSigningProperty("keyPassword")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        create("min") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

val validateReleaseSigning = tasks.register("validateReleaseSigning") {
    group = "verification"
    description = "Checks that the release signing key is configured."
    doLast {
        check(releaseSigningPropertiesFile.isFile) {
            "Release signing is not configured: ${releaseSigningPropertiesFile.path}"
        }
        val storeFile = rootProject.file(releaseSigningProperty("storeFile"))
        check(storeFile.isFile) {
            "Release keystore does not exist: ${storeFile.path}"
        }
    }
}

tasks.configureEach {
    if (name != validateReleaseSigning.name && name.contains("Release", ignoreCase = true)) {
        dependsOn(validateReleaseSigning)
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.androidx.activity)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.core.telecom)
    implementation(libs.gson)
    implementation(libs.okhttp)
    implementation(libs.webrtc)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.work.runtime)
    implementation(libs.unifiedpush.connector)
    implementation(libs.unifiedpush.embedded.fcm)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.work.testing)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
}

val requiredWebRtcJniStrings = listOf(
    // Existing audio-call JNI contract.
    "Lorg/jni_zero/JniZero;",
    "Lorg/jni_zero/CommonApis;",
    "Lorg/webrtc/PeerConnection;",
    "Lorg/webrtc/PeerConnection\$RTCConfiguration;",
    "Lorg/webrtc/PeerConnection\$Observer;",
    "Lorg/webrtc/SdpObserver;",
    "onIceCandidate",
    "onCreateSuccess",

    // Negotiated video tracks and RTP parameters used by the call session.
    "Lorg/webrtc/PeerConnectionFactory;",
    "Lorg/webrtc/MediaStreamTrack\$MediaType;",
    "Lorg/webrtc/RtpSender;",
    "Lorg/webrtc/RtpReceiver;",
    "Lorg/webrtc/RtpTransceiver;",
    "Lorg/webrtc/RtpTransceiver\$RtpTransceiverInit;",
    "Lorg/webrtc/RtpTransceiver\$RtpTransceiverDirection;",
    "Lorg/webrtc/RtpParameters;",
    "Lorg/webrtc/RtpParameters\$Encoding;",
    "Lorg/webrtc/RtpParameters\$Codec;",
    "Lorg/webrtc/RtpParameters\$HeaderExtension;",
    "Lorg/webrtc/RtpParameters\$Rtcp;",
    "Lorg/webrtc/RtpParameters\$DegradationPreference;",
    "Lorg/webrtc/VideoSource;",
    "Lorg/webrtc/NativeAndroidVideoTrackSource;",
    "Lorg/webrtc/VideoTrack;",
    "Lorg/webrtc/VideoSink;",
    "Lorg/webrtc/VideoFrame;",

    // Camera2 primary path, Camera1 fallback, and asynchronous camera callbacks.
    "Lorg/webrtc/CameraEnumerator;",
    "Lorg/webrtc/Camera1Enumerator;",
    "Lorg/webrtc/Camera1Capturer;",
    "Lorg/webrtc/Camera2Enumerator;",
    "Lorg/webrtc/Camera2Capturer;",
    "Lorg/webrtc/CameraVideoCapturer;",
    "Lorg/webrtc/CameraVideoCapturer\$CameraEventsHandler;",
    "Lorg/webrtc/CameraVideoCapturer\$CameraSwitchHandler;",
    "Lorg/webrtc/CapturerObserver;",
    "Lorg/webrtc/VideoCapturer;",
    "Lorg/webrtc/SurfaceTextureHelper;",
    "onCapturerStarted",
    "onCapturerStopped",
    "onFrameCaptured",
    "onCameraError",
    "onCameraDisconnected",
    "onCameraFreezed",
    "onCameraOpening",
    "onFirstFrameAvailable",
    "onCameraClosed",
    "onCameraSwitchDone",
    "onCameraSwitchError",

    // Shared EGL context and the Compose-hosted rendering path.
    "Lorg/webrtc/EglBase;",
    "Lorg/webrtc/EglBase\$Context;",
    "Lorg/webrtc/RendererCommon;",
    "Lorg/webrtc/RendererCommon\$ScalingType;",
    "Lorg/webrtc/EglRenderer;",
    "Lorg/webrtc/SurfaceEglRenderer;",
    "Lorg/webrtc/SurfaceViewRenderer;",
    "onFrame",

    // Default encoder/decoder factories and their hardware/software closures.
    "Lorg/webrtc/DefaultVideoEncoderFactory;",
    "Lorg/webrtc/DefaultVideoDecoderFactory;",
    "Lorg/webrtc/VideoEncoderFactory;",
    "Lorg/webrtc/VideoDecoderFactory;",
    "Lorg/webrtc/HardwareVideoEncoderFactory;",
    "Lorg/webrtc/HardwareVideoDecoderFactory;",
    "Lorg/webrtc/SoftwareVideoEncoderFactory;",
    "Lorg/webrtc/SoftwareVideoDecoderFactory;",
    "Lorg/webrtc/PlatformSoftwareVideoDecoderFactory;",
    "Lorg/webrtc/VideoEncoder;",
    "Lorg/webrtc/VideoEncoder\$Callback;",
    "Lorg/webrtc/VideoDecoder;",
    "Lorg/webrtc/VideoDecoder\$Callback;",
    "Lorg/webrtc/VideoCodecInfo;",
    "Lorg/webrtc/VideoCodecStatus;",
    "createEncoder",
    "createDecoder",
    "initEncode",
    "encode",
    "onEncodedFrame",
    "initDecode",
    "decode",
    "onDecodedFrame",
    "release",
)

fun registerWebRtcJniVerification(
    taskName: String,
    assembleTaskName: String,
    apkPath: String,
) = tasks.register(taskName) {
    group = "verification"
    description = "Checks that R8 preserved WebRTC classes and callbacks used from JNI"
    dependsOn(assembleTaskName)

    doLast {
        val apk = providers.gradleProperty("tinitalkVerifyApk").orNull
            ?.let(::file)
            ?: layout.buildDirectory.file(apkPath).get().asFile
        check(apk.isFile) { "APK not found: $apk" }

        val dexContents = ZipFile(apk).use { archive ->
            archive.entries().asSequence()
                .filter { it.name.matches(Regex("classes\\d*\\.dex")) }
                .map { entry ->
                    archive.getInputStream(entry).use { input ->
                        input.readBytes().toString(Charsets.ISO_8859_1)
                    }
                }
                .toList()
        }
        check(dexContents.isNotEmpty()) { "No DEX files found in $apk" }

        val missing = requiredWebRtcJniStrings.filterNot { required ->
            dexContents.any { required in it }
        }
        check(missing.isEmpty()) {
            "R8 removed WebRTC JNI classes or callbacks from ${apk.name}: ${missing.joinToString()}"
        }
    }
}

val verifyWebRtcJni = registerWebRtcJniVerification(
    taskName = "verifyWebRtcJni",
    assembleTaskName = "assembleRelease",
    apkPath = "outputs/apk/release/app-release.apk",
)

registerWebRtcJniVerification(
    taskName = "verifyWebRtcJniMin",
    assembleTaskName = "assembleMin",
    apkPath = "outputs/apk/min/app-min.apk",
)

tasks.register<Copy>("exportReleaseApk") {
    group = "build"
    description = "Copies the signed release APK to dist with its version in the filename"
    dependsOn(verifyWebRtcJni)
    from(layout.buildDirectory.file("outputs/apk/release/app-release.apk"))
    into(repositoryDir.resolve("dist"))
    rename { "tinitalk-v$tinitalkVersionName.apk" }
}
