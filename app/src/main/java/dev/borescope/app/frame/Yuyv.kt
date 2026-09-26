package dev.borescope.app.frame

/** Packed YUYV 4:2:2 (bytes Y0 U Y1 V per pixel pair, BT.601 limited range) to ARGB_8888. */
object Yuyv {
    /**
     * Converts one frame into [dst] (at least width * height ints).
     * Returns false for frames too short to hold width * height pixels (a
     * truncated transfer); the caller should drop those.
     */
    fun toArgb(src: ByteArray, width: Int, height: Int, dst: IntArray): Boolean {
        require(width > 0 && height > 0 && width % 2 == 0) { "Bad YUYV size ${width}x$height" }
        val pixels = width * height
        require(dst.size >= pixels) { "Destination too small" }
        if (src.size < pixels * 2) return false

        var s = 0
        var d = 0
        repeat(pixels / 2) {
            val y0 = src[s].toInt() and 0xFF
            val u = (src[s + 1].toInt() and 0xFF) - 128
            val y1 = src[s + 2].toInt() and 0xFF
            val v = (src[s + 3].toInt() and 0xFF) - 128
            dst[d++] = argb(y0, u, v)
            dst[d++] = argb(y1, u, v)
            s += 4
        }
        return true
    }

    private fun argb(y: Int, u: Int, v: Int): Int {
        val c = 298 * (y - 16)
        val r = clamp((c + 409 * v + 128) shr 8)
        val g = clamp((c - 100 * u - 208 * v + 128) shr 8)
        val b = clamp((c + 516 * u + 128) shr 8)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun clamp(x: Int) = if (x < 0) 0 else if (x > 255) 255 else x
}
