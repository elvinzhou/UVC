package dev.borescope.uvc

import org.junit.Assert.assertEquals
import org.junit.Test

class UvcControlsTest {

    @Test
    fun supportedBy_readsSpecBitPositions() {
        // D0 brightness, D1 contrast, D9 gain, D12 white balance auto.
        val bm = (1L shl 0) or (1L shl 1) or (1L shl 9) or (1L shl 12)
        assertEquals(
            listOf(UvcControl.BRIGHTNESS, UvcControl.CONTRAST, UvcControl.GAIN, UvcControl.WHITE_BALANCE_AUTO),
            UvcControl.supportedBy(bm),
        )
        assertEquals(emptyList<UvcControl>(), UvcControl.supportedBy(0))
    }

    @Test
    fun selectorsMatchTheSpec() {
        assertEquals(0x02, UvcControl.BRIGHTNESS.selector)
        assertEquals(0x0A, UvcControl.WHITE_BALANCE.selector)
        assertEquals(0x0B, UvcControl.WHITE_BALANCE_AUTO.selector)
        assertEquals(0x05, UvcControl.POWER_LINE.selector)
    }

    @Test
    fun decode_signedAndUnsigned() {
        assertEquals(-64, UvcControl.BRIGHTNESS.decode(0xFFC0))
        assertEquals(64, UvcControl.BRIGHTNESS.decode(0x0040))
        assertEquals(65472, UvcControl.CONTRAST.decode(0xFFC0))
        assertEquals(1, UvcControl.WHITE_BALANCE_AUTO.decode(0x01))
        assertEquals(-32768, UvcControl.HUE.decode(0x8000))
    }

    @Test
    fun encode_roundTrips() {
        for (v in listOf(-32768, -1, 0, 1, 32767)) {
            assertEquals(v, UvcControl.BRIGHTNESS.decode(UvcControl.BRIGHTNESS.encode(v).toLong()))
        }
        assertEquals(0xFFC0, UvcControl.BRIGHTNESS.encode(-64))
        assertEquals(6500, UvcControl.WHITE_BALANCE.decode(UvcControl.WHITE_BALANCE.encode(6500).toLong()))
    }

    @Test
    fun autoSwitchesNameRealControls() {
        UvcControl.entries.mapNotNull { it.autoSwitch }.forEach { UvcControl.valueOf(it) }
    }

    @Test
    fun extensionUnits_parseRecordsAndGuid() {
        // Logitech-style GUID {63610682-5070-49ab-b8cc-b3855e8d221d}, stored little-endian in the first 3 fields.
        val guid = byteArrayOf(
            0x82.toByte(), 0x06, 0x61, 0x63, 0x70, 0x50, 0xab.toByte(), 0x49,
            0xb8.toByte(), 0xcc.toByte(), 0xb3.toByte(), 0x85.toByte(), 0x5e, 0x8d.toByte(), 0x22, 0x1d,
        )
        val record = byteArrayOf(4) + byteArrayOf(0x05, 0, 0, 0, 0, 0, 0, 0) + guid
        val units = ExtensionUnit.fromRecords(record)
        assertEquals(1, units.size)
        assertEquals(4, units[0].unitId)
        assertEquals(listOf(1, 3), units[0].selectors)
        assertEquals("63610682-5070-49ab-b8cc-b3855e8d221d", units[0].guid)
    }
}
