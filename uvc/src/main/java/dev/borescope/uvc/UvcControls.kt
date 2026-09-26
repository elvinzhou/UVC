package dev.borescope.uvc

/**
 * Standard Processing Unit controls (UVC 1.5 §4.2.2.3). [bit] is the control's
 * position in the unit's bmControls; [selector] is its control selector.
 */
enum class UvcControl(
    val bit: Int,
    val selector: Int,
    val size: Int,
    val signed: Boolean,
    val kind: Kind,
    val label: String,
    /** The "auto" control that, when on, makes this one read-only. */
    val autoSwitch: String? = null,
) {
    BRIGHTNESS(0, 0x02, 2, signed = true, Kind.RANGE, "Brightness"),
    CONTRAST(1, 0x03, 2, signed = false, Kind.RANGE, "Contrast", autoSwitch = "CONTRAST_AUTO"),
    HUE(2, 0x06, 2, signed = true, Kind.RANGE, "Hue", autoSwitch = "HUE_AUTO"),
    SATURATION(3, 0x07, 2, signed = false, Kind.RANGE, "Saturation"),
    SHARPNESS(4, 0x08, 2, signed = false, Kind.RANGE, "Sharpness"),
    GAMMA(5, 0x09, 2, signed = false, Kind.RANGE, "Gamma"),
    WHITE_BALANCE(6, 0x0A, 2, signed = false, Kind.RANGE, "White balance (K)", autoSwitch = "WHITE_BALANCE_AUTO"),
    BACKLIGHT(8, 0x01, 2, signed = false, Kind.RANGE, "Backlight compensation"),
    GAIN(9, 0x04, 2, signed = false, Kind.RANGE, "Gain"),
    POWER_LINE(10, 0x05, 1, signed = false, Kind.MENU, "Anti-flicker"),
    HUE_AUTO(11, 0x10, 1, signed = false, Kind.TOGGLE, "Auto hue"),
    WHITE_BALANCE_AUTO(12, 0x0B, 1, signed = false, Kind.TOGGLE, "Auto white balance"),
    CONTRAST_AUTO(18, 0x13, 1, signed = false, Kind.TOGGLE, "Auto contrast");

    enum class Kind { RANGE, TOGGLE, MENU }

    /** Raw little-endian control bytes (as an unsigned number) to a value. */
    fun decode(raw: Long): Int {
        val bits = size * 8
        val unsigned = raw and ((1L shl bits) - 1)
        return if (signed && unsigned >= (1L shl (bits - 1))) (unsigned - (1L shl bits)).toInt() else unsigned.toInt()
    }

    /** A value to raw control bytes (two's complement for signed controls). */
    fun encode(value: Int): Int = (value.toLong() and ((1L shl (size * 8)) - 1)).toInt()

    companion object {
        /** Controls a Processing Unit supports, from its bmControls, in display order. */
        fun supportedBy(bmControls: Long): List<UvcControl> =
            entries.filter { bmControls and (1L shl it.bit) != 0L }

        /** Power-line frequency choices (UVC 1.1 values 0-2; 3 = auto in UVC 1.5). */
        val POWER_LINE_OPTIONS = listOf(0 to "Off", 1 to "50 Hz", 2 to "60 Hz", 3 to "Auto")
    }
}

/** A control's current state as read from the camera. */
data class ControlValue(
    val control: UvcControl,
    val min: Int,
    val max: Int,
    val step: Int,
    val default: Int,
    val current: Int,
)

/** A vendor-defined Extension Unit, e.g. where a scope might expose LED control. */
data class ExtensionUnit(val unitId: Int, val bmControls: Long, val guid: String) {
    /** Control selectors (1-based) this unit says it implements. */
    val selectors: List<Int> get() = (0 until 64).filter { bmControls and (1L shl it) != 0L }.map { it + 1 }

    override fun toString() = "XU $unitId {$guid} controls=${selectors.joinToString(",")}"

    companion object {
        internal const val RECORD_SIZE = 25

        /** Parses UvcNative.nativeGetExtensionUnits records. */
        internal fun fromRecords(bytes: ByteArray): List<ExtensionUnit> =
            (0 until bytes.size / RECORD_SIZE).map { r ->
                val o = r * RECORD_SIZE
                var bm = 0L
                for (i in 0 until 8) bm = bm or ((bytes[o + 1 + i].toLong() and 0xFF) shl (8 * i))
                ExtensionUnit(bytes[o].toInt() and 0xFF, bm, formatGuid(bytes, o + 9))
            }

        /** USB descriptors store GUIDs with the first three fields little-endian. */
        internal fun formatGuid(b: ByteArray, o: Int): String {
            fun hex(i: Int) = "%02x".format(b[o + i].toInt() and 0xFF)
            return listOf(3, 2, 1, 0).joinToString("") { hex(it) } + "-" +
                listOf(5, 4).joinToString("") { hex(it) } + "-" +
                listOf(7, 6).joinToString("") { hex(it) } + "-" +
                (8..9).joinToString("") { hex(it) } + "-" +
                (10..15).joinToString("") { hex(it) }
        }
    }
}
