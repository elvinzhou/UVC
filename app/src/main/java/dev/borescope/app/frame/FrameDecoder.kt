package dev.borescope.app.frame

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.borescope.uvc.UvcFormat

/**
 * Turns camera frames into Bitmaps. Only call it from the native frame
 * thread: it reuses one scratch buffer.
 */
class FrameDecoder {
    private var argb = IntArray(0)

    /** Returns null for frames it can't (or shouldn't) show, e.g. corrupt or truncated ones. */
    fun decode(data: ByteArray, width: Int, height: Int, type: Int): Bitmap? = when (type) {
        UvcFormat.Type.MJPEG.ordinal -> BitmapFactory.decodeByteArray(data, 0, data.size)
        UvcFormat.Type.YUYV.ordinal -> decodeYuyv(data, width, height)
        else -> null
    }

    private fun decodeYuyv(data: ByteArray, width: Int, height: Int): Bitmap? {
        if (width <= 0 || height <= 0 || width % 2 != 0) return null
        if (argb.size < width * height) argb = IntArray(width * height)
        if (!Yuyv.toArgb(data, width, height, argb)) return null
        // A new Bitmap per frame: Compose may still be drawing the previous one.
        return Bitmap.createBitmap(argb, 0, width, width, height, Bitmap.Config.ARGB_8888)
    }
}
