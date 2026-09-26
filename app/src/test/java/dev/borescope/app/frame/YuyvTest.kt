package dev.borescope.app.frame

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YuyvTest {

    private fun frame(vararg quads: Int) = ByteArray(quads.size) { quads[it].toByte() }

    @Test
    fun convertsReferenceColors() {
        // Pairs: black, white, red, and a (Y0, Y1) pair sharing one chroma sample.
        val src = frame(
            16, 128, 16, 128,
            235, 128, 235, 128,
            81, 90, 81, 240,
            16, 128, 235, 128,
        )
        val dst = IntArray(8)
        assertTrue(Yuyv.toArgb(src, 8, 1, dst))
        assertArrayEquals(
            intArrayOf(
                0xFF000000.toInt(), 0xFF000000.toInt(),
                0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(),
                0xFFFF0000.toInt(), 0xFFFF0000.toInt(),
                0xFF000000.toInt(), 0xFFFFFFFF.toInt(),
            ),
            dst,
        )
    }

    @Test
    fun clampsOutOfRangeValues() {
        // Y below/above the legal 16..235 range must not wrap around.
        val dst = IntArray(2)
        assertTrue(Yuyv.toArgb(frame(0, 128, 255, 128), 2, 1, dst))
        assertArrayEquals(intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), dst)
    }

    @Test
    fun rejectsTruncatedFrame() {
        assertFalse(Yuyv.toArgb(ByteArray(2 * 2 * 2 - 1), 2, 2, IntArray(4)))
    }

    @Test
    fun acceptsFrameWithTrailingPadding() {
        assertTrue(Yuyv.toArgb(ByteArray(2 * 2 * 2 + 16) { 16 }, 2, 2, IntArray(4)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOddWidth() {
        Yuyv.toArgb(ByteArray(6), 3, 1, IntArray(3))
    }
}
