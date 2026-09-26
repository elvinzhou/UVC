# JNI looks up FrameListener.onFrame by name.
-keep interface dev.borescope.uvc.FrameListener { *; }
-keepclasseswithmembernames class * { native <methods>; }
