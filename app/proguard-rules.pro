# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
# AloNativeVideoCircleRecorder sets the MPEG-4 (AAC audio) output format on CameraX's recorder builder by reflection.
-keepclassmembers class androidx.camera.video.Recorder$Builder { *** mMediaSpecBuilder; }
# It also reads Recorder.mVideoEncoder to know when a camera flip mid-circle has finished (stop is deferred until then).
-keepclassmembers class androidx.camera.video.Recorder { *** mVideoEncoder; }

# R8 is here for speed and size, not to hide the code: names stay, so reports of crashes on people's phones
# (AloCrashReporter) read as they are, without a mapping file per release.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable

# Native code finds these classes, methods and fields by name: R8 cannot see that, and a missing one kills the process the
# moment the library loads (the first call crashed on org.jni_zero.JniInit). The list comes from the class names inside the
# APK's .so files; check it again when a native library is added.
-keep class org.signal.libsignal.** { *; }
-keep class org.webrtc.** { *; }
-keep class org.jni_zero.** { *; }
-keep class livekit.org.webrtc.** { *; }
-keep class livekit.org.jni_zero.** { *; }
-keep class org.maplibre.** { *; }
-keep class com.google.gson.** { *; }
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.libraries.barhopper.** { *; }
-keep class com.google.photos.vision.barhopper.** { *; }
-keep class androidx.graphics.path.** { *; }

# Group calls: LiveKit reads its protocol messages (protobuf-lite) by reflection on their fields.
-keep class livekit.** { *; }
-keep class io.livekit.android.** { *; }
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
