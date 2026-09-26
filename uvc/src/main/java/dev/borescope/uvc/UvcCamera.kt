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

    private fun checkOpen(): Long = handle.also { check(it != 0L) { "Camera is closed" } }

    companion object {
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
