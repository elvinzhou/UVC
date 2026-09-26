# Shipped with the uvc library so any app using it keeps these under R8.
# JNI looks up FrameListener.onFrame by name.
-keep interface dev.borescope.uvc.FrameListener { *; }
-keepclasseswithmembernames class * { native <methods>; }
