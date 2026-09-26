package dev.borescope.app.frame

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.createBitmap
import dev.borescope.uvc.UvcFormat

/**
 * Turns camera frames into Bitmaps without allocating one per frame.
 *
 * Decodes into a ring of [POOL_SIZE] reusable bitmaps (BitmapFactory's
 * inBitmap for MJPEG, setPixels for YUYV). The screen shows the newest one
 * while the renderer may still be reading the previous one, so a bitmap is
 * overwritten only [POOL_SIZE] frames after it was handed out: copy it if you
 * keep it longer. Only call from the native frame thread.
 */
class FrameDecoder {
    private val pool = arrayOfNulls<Bitmap>(POOL_SIZE)
    private var next = 0
    private var argb = IntArray(0)
    private val options = BitmapFactory.Options().apply { inMutable = true }

    /** Returns null for frames it can't (or shouldn't) show, e.g. corrupt or truncated ones. */
    fun decode(data: ByteArray, length: Int, width: Int, height: Int, type: Int): Bitmap? = when (type) {
        UvcFormat.Type.MJPEG.ordinal -> decodeMjpeg(data, length)
        UvcFormat.Type.YUYV.ordinal -> decodeYuyv(data, length, width, height)
        else -> null
    }

    private fun decodeMjpeg(data: ByteArray, length: Int): Bitmap? {
        val slot = next
        // inBitmap needs a mutable bitmap at least as large as the result; the
        // first frame of each size (and any decoder complaint) falls back to a fresh one.
        options.inBitmap = pool[slot]
        val bitmap = try {
            BitmapFactory.decodeByteArray(data, 0, length, options)
        } catch (_: IllegalArgumentException) {
            options.inBitmap = null
            BitmapFactory.decodeByteArray(data, 0, length, options)
        } ?: return null
        return handOut(slot, bitmap)
    }

    private fun decodeYuyv(data: ByteArray, length: Int, width: Int, height: Int): Bitmap? {
        if (width <= 0 || height <= 0 || width % 2 != 0) return null
        if (argb.size < width * height) argb = IntArray(width * height)
        if (!Yuyv.toArgb(data, width, height, argb, length)) return null
        val slot = next
        val bitmap = pool[slot]?.takeIf { it.width == width && it.height == height && it.isMutable }
            ?: createBitmap(width, height)
        bitmap.setPixels(argb, 0, width, 0, 0, width, height)
        return handOut(slot, bitmap)
    }

    private fun handOut(slot: Int, bitmap: Bitmap): Bitmap {
        pool[slot] = bitmap
        next = (slot + 1) % POOL_SIZE
        return bitmap
    }

    companion object {
        const val POOL_SIZE = 3
    }
}
