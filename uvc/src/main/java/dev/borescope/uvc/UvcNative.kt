package dev.borescope.uvc

/** Raw JNI surface. Keep in sync with src/main/cpp/uvc_jni.cpp. */
internal object UvcNative {
    init {
        System.loadLibrary("uvcjni")
    }

    @JvmStatic external fun nativeOpen(fd: Int): Long
    @JvmStatic external fun nativeGetFormats(handle: Long): IntArray
    @JvmStatic external fun nativeGetDiagnostics(handle: Long): String
    @JvmStatic external fun nativeStart(
        handle: Long, type: Int, width: Int, height: Int, fps: Int, listener: FrameListener,
    ): Int
    @JvmStatic external fun nativeStop(handle: Long)
    @JvmStatic external fun nativeClose(handle: Long)
}
