# ONNX Runtime, OpenCV and ML Kit use JNI/reflection; keep their entry points.
-keep class ai.onnxruntime.** { *; }
-keep class org.opencv.** { *; }
-keep class com.google.mlkit.** { *; }
-dontwarn ai.onnxruntime.**
-dontwarn org.opencv.**
