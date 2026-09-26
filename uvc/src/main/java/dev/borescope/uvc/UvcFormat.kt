package dev.borescope.uvc

/** One stream mode the camera advertises in its USB descriptors. */
data class UvcFormat(
    val type: Type,
    val width: Int,
    val height: Int,
    val fps: Int,
) {
    /** Ordinals must match FormatType in uvc_jni.cpp. */
    enum class Type { MJPEG, YUYV, H264, OTHER }

    override fun toString() = "$type ${width}x$height@$fps"

    internal companion object {
        fun fromFlat(flat: IntArray): List<UvcFormat> =
            flat.toList().chunked(4).map { (t, w, h, fps) ->
                UvcFormat(Type.entries.getOrElse(t) { Type.OTHER }, w, h, fps)
            }
    }
}

/**
 * Called on the native streaming thread for every complete frame.
 * For MJPEG, [data] is a complete JPEG image. Don't block here: hand off and return.
 */
fun interface FrameListener {
    fun onFrame(data: ByteArray, width: Int, height: Int, type: Int)
}
