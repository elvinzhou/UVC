package dev.borescope.uvc

/** One stream mode the camera advertises in its USB descriptors. */
data class UvcFormat(
    val type: Type,
    val width: Int,
    val height: Int,
    val fps: Int,
    /** Four-character code from the format GUID, e.g. "MJPG", "YUY2", "NV12", "H264". */
    val fourcc: String = "",
) {
    /** Ordinals must match FormatType in uvc_jni.cpp. */
    enum class Type { MJPEG, YUYV, H264, OTHER }

    /** Whether the app can turn frames of this format into a picture. */
    val isDisplayable: Boolean get() = type == Type.MJPEG || type == Type.YUYV

    override fun toString() = "$type ${width}x$height@$fps"

    companion object {
        /** Ints per format in the array from UvcNative.nativeGetFormats. */
        internal const val STRIDE = 5

        internal fun fromFlat(flat: IntArray): List<UvcFormat> =
            flat.toList().chunked(STRIDE).filter { it.size == STRIDE }.map { (t, cc, w, h, fps) ->
                UvcFormat(Type.entries.getOrElse(t) { Type.OTHER }, w, h, fps, fourccToString(cc))
            }

        /**
         * Unpacks a little-endian FOURCC. Trailing NUL/space padding is dropped;
         * other non-printable bytes become '?'.
         */
        fun fourccToString(packed: Int): String =
            String(CharArray(4) { i ->
                when (val b = (packed ushr (8 * i)) and 0xFF) {
                    0 -> ' '
                    in 0x20..0x7E -> b.toChar()
                    else -> '?'
                }
            }).trimEnd()

        /**
         * Best default for a borescope: the largest MJPEG mode (compressed, so
         * high resolutions fit through USB 2.0), else the largest YUYV mode.
         * Formats the app can't display (H.264, other raw layouts) are never picked.
         */
        fun preferred(formats: List<UvcFormat>): UvcFormat? =
            formats.filter { it.type == Type.MJPEG }.maxByOrNull { it.width * it.height }
                ?: formats.filter { it.type == Type.YUYV }.maxByOrNull { it.width * it.height }
    }
}

/**
 * Called on the native streaming thread for every complete frame.
 * For MJPEG, [data] is a complete JPEG image; for YUYV, packed 4:2:2 pixels.
 * Don't block here and never wait on the thread that stops streaming:
 * stopping joins this thread.
 */
fun interface FrameListener {
    fun onFrame(data: ByteArray, width: Int, height: Int, type: Int)
}
