# Native ML runtimes call back into these classes through JNI by name.
-keep class ai.onnxruntime.** { *; }
-keep class com.google.mediapipe.** { *; }
# MediaPipe's protos are parsed reflectively (protobuf-lite).
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }

# Referenced by libraries but not present on Android.
-dontwarn javax.annotation.**
-dontwarn javax.lang.model.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn org.checkerframework.**
-dontwarn com.google.auto.value.**
-dontwarn com.google.mediapipe.proto.**

# WorkManager instantiates workers by class name.
-keep class * extends androidx.work.ListenableWorker { <init>(android.content.Context, androidx.work.WorkerParameters); }
