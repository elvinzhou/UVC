package dev.borescope.uvc

/** Raw JNI surface. Keep in sync with src/main/cpp/uvc_jni.cpp. */
internal object UvcNative {
    init {
        System.loadLibrary("uvcjni")
    }

    @JvmStatic external fun nativeOpen(fd: Int): Long
    @JvmStatic external fun nativeGetFormats(handle: Long): IntArray
    @JvmStatic external fun nativeGetDiagnostics(handle: Long): String
    @JvmStatic external fun nativeGetProcessingUnits(handle: Long): LongArray
    @JvmStatic external fun nativeGetExtensionUnits(handle: Long): ByteArray
    @JvmStatic external fun nativeGetCtrl(handle: Long, unit: Int, selector: Int, len: Int, req: Int): Long
    @JvmStatic external fun nativeSetCtrl(handle: Long, unit: Int, selector: Int, len: Int, value: Int): Int
    @JvmStatic external fun nativeGetCtrlBytes(handle: Long, unit: Int, selector: Int, req: Int): ByteArray?
    @JvmStatic external fun nativeSetCtrlBytes(handle: Long, unit: Int, selector: Int, data: ByteArray): Int
    @JvmStatic external fun nativeStart(
        handle: Long, type: Int, width: Int, height: Int, fps: Int, listener: FrameListener,
    ): Int
    @JvmStatic external fun nativeStop(handle: Long)
    @JvmStatic external fun nativeClose(handle: Long)
}
