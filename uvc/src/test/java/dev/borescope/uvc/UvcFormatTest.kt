package dev.borescope.uvc

import dev.borescope.uvc.UvcFormat.Type
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UvcFormatTest {

    private fun cc(s: String) = s.foldIndexed(0) { i, acc, c -> acc or (c.code shl (8 * i)) }

    @Test
    fun fromFlat_parsesRecords() {
        val flat = intArrayOf(
            0, cc("MJPG"), 1280, 720, 30,
            1, cc("YUY2"), 640, 480, 15,
        )
        assertEquals(
            listOf(
                UvcFormat(Type.MJPEG, 1280, 720, 30, "MJPG"),
                UvcFormat(Type.YUYV, 640, 480, 15, "YUY2"),
            ),
            UvcFormat.fromFlat(flat),
        )
    }

    @Test
    fun fromFlat_unknownTypeBecomesOther() {
        assertEquals(Type.OTHER, UvcFormat.fromFlat(intArrayOf(99, 0, 1, 1, 1)).single().type)
    }

    @Test
    fun fromFlat_emptyArray() {
        assertEquals(emptyList<UvcFormat>(), UvcFormat.fromFlat(IntArray(0)))
    }

    @Test
    fun fromFlat_ignoresTruncatedTrailingRecord() {
        assertEquals(1, UvcFormat.fromFlat(intArrayOf(0, 0, 1, 1, 1, 0, 0)).size)
    }

    @Test
    fun typeOrdinals_matchNativeEnum() {
        // Must match FormatType in uvc_jni.cpp.
        assertEquals(0, Type.MJPEG.ordinal)
        assertEquals(1, Type.YUYV.ordinal)
        assertEquals(2, Type.H264.ordinal)
        assertEquals(3, Type.OTHER.ordinal)
    }

    @Test
    fun toString_isHumanReadable() {
        assertEquals("MJPEG 1920x1080@30", UvcFormat(Type.MJPEG, 1920, 1080, 30).toString())
    }

    @Test
    fun fourccToString_unpacksLittleEndian() {
        assertEquals("YUY2", UvcFormat.fourccToString(cc("YUY2")))
        assertEquals("NV12", UvcFormat.fourccToString(cc("NV12")))
    }

    @Test
    fun fourccToString_zeroIsEmptyAndGarbageIsMasked() {
        assertEquals("", UvcFormat.fourccToString(0))
        assertEquals("A?B", UvcFormat.fourccToString(cc("A") or (0x01 shl 8) or (cc("B") shl 16)))
    }

    @Test
    fun preferred_picksLargestMjpeg() {
        val formats = listOf(
            UvcFormat(Type.YUYV, 1920, 1080, 5),
            UvcFormat(Type.MJPEG, 640, 480, 30),
            UvcFormat(Type.MJPEG, 1280, 720, 30),
        )
        assertEquals(UvcFormat(Type.MJPEG, 1280, 720, 30), UvcFormat.preferred(formats))
    }

    @Test
    fun preferred_fallsBackToYuyv() {
        val formats = listOf(
            UvcFormat(Type.H264, 1920, 1080, 30),
            UvcFormat(Type.YUYV, 320, 240, 30),
            UvcFormat(Type.YUYV, 640, 480, 30),
        )
        assertEquals(UvcFormat(Type.YUYV, 640, 480, 30), UvcFormat.preferred(formats))
    }

    @Test
    fun preferred_neverPicksUndisplayableFormats() {
        assertNull(UvcFormat.preferred(listOf(UvcFormat(Type.H264, 1920, 1080, 30), UvcFormat(Type.OTHER, 640, 480, 30))))
        assertNull(UvcFormat.preferred(emptyList()))
    }

    @Test
    fun isDisplayable() {
        assertTrue(UvcFormat(Type.MJPEG, 1, 1, 1).isDisplayable)
        assertTrue(UvcFormat(Type.YUYV, 1, 1, 1).isDisplayable)
        assertFalse(UvcFormat(Type.H264, 1, 1, 1).isDisplayable)
        assertFalse(UvcFormat(Type.OTHER, 1, 1, 1).isDisplayable)
    }
}
