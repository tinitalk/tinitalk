# WebRTC native code resolves these Java classes and callbacks by name.
-keep class org.webrtc.** { *; }

# WebRTC resolves these annotated bootstrap classes and callbacks by name.
# Do not retain unused Java APIs: JniZero.setJniClassLoader references a generated
# class that the standalone WebRTC AAR does not ship.
-keepclasseswithmembers class org.jni_zero.** {
    @org.jni_zero.CalledByNative <methods>;
}

# Gson reads these model fields by their JSON names at runtime.
-keepclassmembers class org.tinitalk.data.** {
    <fields>;
}
