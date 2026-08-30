import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

if (file("google-services.json").isFile) {
    apply(plugin = "com.google.gms.google-services")
}

val tinitalkServerUrl = providers.gradleProperty("tinitalkServerUrl")
    .getOrElse("https://tinitalk.example.com")
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")

val tinitalkAbi = providers.gradleProperty("tinitalkAbi").getOrElse("all")
require(tinitalkAbi == "arm64" || tinitalkAbi == "all") {
    "tinitalkAbi must be 'arm64' or 'all'"
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
    compileSdk = 36

    defaultConfig {
        applicationId = "org.tinitalk"
        minSdk = 26
        targetSdk = 36
        versionCode = 11
        versionName = "0.7"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("boolean", "FORCE_RELAY", providers.gradleProperty("tinitalkForceRelay").getOrElse("false"))
        buildConfigField("String", "COMMIT_HASH", "\"$commitHash\"")
        buildConfigField("String", "SERVER_URL", "\"$tinitalkServerUrl\"")
        if (tinitalkAbi == "arm64") {
            ndk {
                abiFilters += "arm64-v8a"
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.androidx.activity)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.core.telecom)
    implementation(libs.gson)
    implementation(libs.okhttp)
    implementation(libs.webrtc)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
}

val requiredWebRtcJniStrings = listOf(
    // Existing audio-call JNI contract.
    "Lorg/jni_zero/JniInit;",
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

tasks.register("verifyWebRtcJni") {
    group = "verification"
    description = "Checks that R8 preserved WebRTC classes and callbacks used from JNI"
    dependsOn("assembleRelease")

    doLast {
        val apk = providers.gradleProperty("tinitalkVerifyApk").orNull
            ?.let(::file)
            ?: layout.buildDirectory.file("outputs/apk/release/app-release.apk").get().asFile
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
