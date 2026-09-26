package dev.borescope.uvc

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager

/**
 * An open UVC camera. Requires USB permission for [UsbDevice] beforehand
 * (see [UsbCameraManager]). Not thread-safe: call from one thread.
 */
class UvcCamera private constructor(
    private val connection: UsbDeviceConnection,
    private var handle: Long,
) : CameraDevice {

    /** All stream modes the camera advertises, e.g. "MJPEG 1280x720@30". */
    override val formats: List<UvcFormat> by lazy {
        UvcFormat.fromFlat(UvcNative.nativeGetFormats(checkOpen()))
    }

    override val diagnostics: String by lazy { UvcNative.nativeGetDiagnostics(checkOpen()) }

    /** Best default for a borescope, see [UvcFormat.preferred]. */
    fun preferredFormat(): UvcFormat? = UvcFormat.preferred(formats)

    override fun startStreaming(format: UvcFormat, listener: FrameListener) {
        val rc = UvcNative.nativeStart(
            checkOpen(), format.type.ordinal, format.width, format.height, format.fps, listener,
        )
        check(rc == 0) { "Failed to start $format (uvc error $rc)" }
    }

    override fun stopStreaming() {
        if (handle != 0L) UvcNative.nativeStop(handle)
    }

    override fun close() {
        if (handle != 0L) {
            UvcNative.nativeClose(handle)
            handle = 0L
            connection.close()   // closes the fd after libusb is done with it
        }
    }

    override fun readControls(): List<ControlValue> {
        val h = checkOpen()
        val units = UvcNative.nativeGetProcessingUnits(h)
        if (units.size < 2) return emptyList()
        val unit = units[0].toInt()   // cameras have one Processing Unit in practice
        return UvcControl.supportedBy(units[1]).mapNotNull { c ->
            fun get(req: Int): Int? = UvcNative.nativeGetCtrl(h, unit, c.selector, c.size, req)
                .takeIf { it >= 0 }?.let(c::decode)
            val current = get(GET_CUR) ?: return@mapNotNull null
            when (c.kind) {
                UvcControl.Kind.TOGGLE -> ControlValue(c, 0, 1, 1, get(GET_DEF) ?: current, current)
                UvcControl.Kind.MENU -> ControlValue(c, get(GET_MIN) ?: 0, get(GET_MAX) ?: 2, 1, get(GET_DEF) ?: current, current)
                UvcControl.Kind.RANGE -> {
                    val min = get(GET_MIN) ?: return@mapNotNull null
                    val max = get(GET_MAX) ?: return@mapNotNull null
                    if (max <= min) return@mapNotNull null
                    ControlValue(c, min, max, (get(GET_RES) ?: 1).coerceAtLeast(1), get(GET_DEF) ?: current, current)
                }
            }
        }
    }

    override fun setControl(control: UvcControl, value: Int) {
        val h = checkOpen()
        val units = UvcNative.nativeGetProcessingUnits(h)
        check(units.size >= 2) { "Camera has no processing unit" }
        val rc = UvcNative.nativeSetCtrl(h, units[0].toInt(), control.selector, control.size, control.encode(value))
        check(rc == 0) { "Camera refused ${control.label} = $value (error $rc)" }
    }

    override val extensionUnits: List<ExtensionUnit>
        get() = ExtensionUnit.fromRecords(UvcNative.nativeGetExtensionUnits(checkOpen()))

    override fun readExtensionControl(unitId: Int, selector: Int): ByteArray? =
        UvcNative.nativeGetCtrlBytes(checkOpen(), unitId, selector, GET_CUR)

    override fun writeExtensionControl(unitId: Int, selector: Int, data: ByteArray) {
        val rc = UvcNative.nativeSetCtrlBytes(checkOpen(), unitId, selector, data)
        check(rc == 0) { "Camera refused XU $unitId/$selector (error $rc)" }
    }

    private fun checkOpen(): Long = handle.also { check(it != 0L) { "Camera is closed" } }

    companion object {
        // UVC request codes (UVC 1.5 Table A-8).
        private const val GET_CUR = 0x81
        private const val GET_MIN = 0x82
        private const val GET_MAX = 0x83
        private const val GET_RES = 0x84
        private const val GET_DEF = 0x87

        fun open(usbManager: UsbManager, device: UsbDevice): UvcCamera {
            val connection = usbManager.openDevice(device)
                ?: error("openDevice failed. Is USB permission granted?")
            val handle = UvcNative.nativeOpen(connection.fileDescriptor)
            if (handle == 0L) {
                connection.close()
                error("Native open failed; see logcat tag 'uvc-native'")
            }
            return UvcCamera(connection, handle)
        }
    }
}
