package dev.borescope.uvc

import org.junit.Assert.assertEquals
import org.junit.Test

class UvcFormatTest {

    @Test
    fun fromFlat_parsesQuadruples() {
        val flat = intArrayOf(
            0, 1280, 720, 30,
            1, 640, 480, 15,
        )
        assertEquals(
            listOf(
                UvcFormat(UvcFormat.Type.MJPEG, 1280, 720, 30),
                UvcFormat(UvcFormat.Type.YUYV, 640, 480, 15),
            ),
            UvcFormat.fromFlat(flat),
        )
    }

    @Test
    fun fromFlat_unknownTypeBecomesOther() {
        assertEquals(UvcFormat.Type.OTHER, UvcFormat.fromFlat(intArrayOf(99, 1, 1, 1)).single().type)
    }

    @Test
    fun fromFlat_emptyArray() {
        assertEquals(emptyList<UvcFormat>(), UvcFormat.fromFlat(IntArray(0)))
    }

    @Test
    fun typeOrdinals_matchNativeEnum() {
        // Must match FormatType in uvc_jni.cpp.
        assertEquals(0, UvcFormat.Type.MJPEG.ordinal)
        assertEquals(1, UvcFormat.Type.YUYV.ordinal)
        assertEquals(2, UvcFormat.Type.H264.ordinal)
        assertEquals(3, UvcFormat.Type.OTHER.ordinal)
    }

    @Test
    fun toString_isHumanReadable() {
        assertEquals("MJPEG 1920x1080@30", UvcFormat(UvcFormat.Type.MJPEG, 1920, 1080, 30).toString())
    }
}
