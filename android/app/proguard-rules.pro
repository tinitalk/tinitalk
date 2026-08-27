# WebRTC native code resolves these Java classes and callbacks by name.
-keep class org.webrtc.** { *; }

# WebRTC loads its JNI bootstrap class by this exact name from native code.
-keep class org.jni_zero.JniInit { *; }

# Gson reads these model fields by their JSON names at runtime.
-keepclassmembers class org.tinitalk.data.** {
    <fields>;
}
