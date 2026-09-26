package dev.borescope.app.capture

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

class MjpegFramesTest {

    /** A real 32x16 baseline JPEG from the JDK encoder, which writes the standard tables. */
    private val jpeg: ByteArray by lazy {
        javaClass.classLoader!!.getResourceAsStream("baseline.jpg").use { it.readBytes() }
    }

    /** What a UVC camera sends: the same JPEG with every DHT segment removed. */
    private fun withoutDht(src: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(src, 0, 2)
        var i = 2
        while (true) {
            val marker = src[i + 1].toInt() and 0xFF
            if (marker == 0xDA) {
                out.write(src, i, src.size - i)
                return out.toByteArray()
            }
            val len = ((src[i + 2].toInt() and 0xFF) shl 8) or (src[i + 3].toInt() and 0xFF)
            if (marker != 0xC4) out.write(src, i, len + 2)
            i += len + 2
        }
    }

    /**
     * Decodes with the JDK's JPEG decoder (javax.imageio). Unit tests compile
     * against android.jar, which lacks java.awt, hence the reflection.
     */
    private fun pixels(bytes: ByteArray): IntArray {
        val imageIo = Class.forName("javax.imageio.ImageIO")
        val img = imageIo.getMethod("read", InputStream::class.java).invoke(null, ByteArrayInputStream(bytes))
            ?: error("ImageIO couldn't decode it")
        val w = img.javaClass.getMethod("getWidth").invoke(img) as Int
        val h = img.javaClass.getMethod("getHeight").invoke(img) as Int
        val getRgb = img.javaClass.getMethod(
            "getRGB", Int::class.java, Int::class.java, Int::class.java, Int::class.java,
            IntArray::class.java, Int::class.java, Int::class.java,
        )
        return getRgb.invoke(img, 0, 0, w, h, null, 0, w) as IntArray
    }

    @Test
    fun insertsStandardTablesSoFramesDecodeIdentically() {
        val cameraFrame = withoutDht(jpeg)
        assertEquals(false, MjpegFrames.markerBeforeScan(cameraFrame, cameraFrame.size, 0xC4))

        val fixed = MjpegFrames.toJpegFile(cameraFrame)!!
        assertEquals(true, MjpegFrames.markerBeforeScan(fixed, fixed.size, 0xC4))
        assertArrayEquals(pixels(jpeg), pixels(fixed))
    }

    @Test
    fun leavesFramesWithTablesUntouched() {
        assertArrayEquals(jpeg, MjpegFrames.toJpegFile(jpeg))
    }

    @Test
    fun honoursLength() {
        val padded = jpeg + ByteArray(100)
        assertArrayEquals(jpeg, MjpegFrames.toJpegFile(padded, jpeg.size))
    }

    @Test
    fun addsExifOrientationAndStillDecodes() {
        val fixed = MjpegFrames.toJpegFile(withoutDht(jpeg), exifOrientation = 6)!!
        // APP1 right after SOI, Orientation value 6.
        assertEquals(0xE1, fixed[3].toInt() and 0xFF)
        assertEquals("Exif", String(fixed, 6, 4))
        // SOI 2, marker 2, length 2, "Exif\0\0" 6, TIFF header 8, count 2, tag 2, type 2, count 4, high byte 1
        assertEquals(6, fixed[2 + 2 + 2 + 6 + 8 + 2 + 2 + 2 + 4 + 1].toInt())
        assertArrayEquals(pixels(jpeg), pixels(fixed))
    }

    @Test
    fun rejectsNonJpeg() {
        assertNull(MjpegFrames.toJpegFile(byteArrayOf(1, 2, 3, 4, 5)))
        assertNull(MjpegFrames.toJpegFile(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0, 0, 0)))
    }

    @Test
    fun standardTableSegmentIsWellFormed() {
        val dht = MjpegFrames.STANDARD_DHT
        assertEquals(420, dht.size)
        assertEquals(0x01A2, ((dht[2].toInt() and 0xFF) shl 8) or (dht[3].toInt() and 0xFF))
    }

    @Test
    fun exifOrientationMatchesPreviewTransform() {
        assertEquals(listOf(1, 6, 3, 8), listOf(0, 90, 180, 270).map { MjpegFrames.exifOrientation(it, false) })
        assertEquals(listOf(2, 7, 4, 5), listOf(0, 90, 180, 270).map { MjpegFrames.exifOrientation(it, true) })
        assertEquals(6, MjpegFrames.exifOrientation(450, false))
        assertEquals(8, MjpegFrames.exifOrientation(-90, false))
    }

    @Test
    fun exifSegmentLengthMatchesHeader() {
        val app1 = MjpegFrames.exifApp1(3)
        val declared = ((app1[2].toInt() and 0xFF) shl 8) or (app1[3].toInt() and 0xFF)
        assertEquals(app1.size - 2, declared)
        assertEquals(36, app1.size)
    }
}
