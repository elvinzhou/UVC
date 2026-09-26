package dev.borescope.app.capture

import kotlin.math.roundToInt

/** Encoder settings derived from the camera stream. Pure, so it's unit-tested. */
data class VideoSpec(val width: Int, val height: Int, val fps: Int, val bitRate: Int) {
    companion object {
        /** Hardware AVC encoders reliably handle up to 1080p. */
        const val MAX_WIDTH = 1920
        const val MAX_HEIGHT = 1080

        fun forStream(width: Int, height: Int, fps: Int): VideoSpec {
            require(width > 0 && height > 0) { "Bad stream size ${width}x$height" }
            // Fit inside 1920x1080 in either orientation, keeping the aspect ratio.
            val landscape = width >= height
            val maxW = if (landscape) MAX_WIDTH else MAX_HEIGHT
            val maxH = if (landscape) MAX_HEIGHT else MAX_WIDTH
            val scale = minOf(1.0, maxW.toDouble() / width, maxH.toDouble() / height)
            // Multiples of 16 keep every encoder happy (macroblock size).
            val w = align16((width * scale).roundToInt())
            val h = align16((height * scale).roundToInt())
            val rate = fps.coerceIn(1, 60)
            // ~0.15 bits per pixel per frame: clean for low-motion inspection video.
            val bits = (w.toLong() * h * rate * 15 / 100).coerceIn(1_000_000, 20_000_000).toInt()
            return VideoSpec(w, h, rate, bits)
        }

        private fun align16(x: Int) = maxOf(16, x / 16 * 16)
    }
}
