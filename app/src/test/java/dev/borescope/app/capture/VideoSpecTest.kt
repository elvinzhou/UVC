package dev.borescope.app.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoSpecTest {

    @Test
    fun keepsCommonSizes() {
        val spec = VideoSpec.forStream(1280, 720, 30)
        assertEquals(1280, spec.width)
        assertEquals(720, spec.height)
        assertEquals(30, spec.fps)
    }

    @Test
    fun alignsToMacroblocks() {
        val spec = VideoSpec.forStream(1920, 1080, 30)
        assertEquals(1920, spec.width)
        assertEquals(1072, spec.height)   // 1080 isn't a multiple of 16
    }

    @Test
    fun scalesDownOversizedStreams() {
        val spec = VideoSpec.forStream(2592, 1944, 15)   // 5 MP, 4:3
        assertTrue(spec.width <= 1920 && spec.height <= 1080)
        assertEquals(1440, spec.width)
        assertEquals(1072, spec.height)
    }

    @Test
    fun handlesPortraitStreams() {
        val spec = VideoSpec.forStream(1080, 1920, 30)
        assertEquals(1072, spec.width)
        assertEquals(1920, spec.height)
    }

    @Test
    fun clampsFpsAndBitRate() {
        val tiny = VideoSpec.forStream(160, 120, 0)
        assertEquals(1, tiny.fps)
        assertEquals(1_000_000, tiny.bitRate)
        val huge = VideoSpec.forStream(1920, 1080, 120)
        assertEquals(60, huge.fps)
        assertEquals(18_524_160, huge.bitRate)
    }

    @Test
    fun neverProducesZeroSize() {
        val spec = VideoSpec.forStream(8, 8, 30)
        assertEquals(16, spec.width)
        assertEquals(16, spec.height)
    }
}
