package dev.borescope.app.camera

import dev.borescope.uvc.CameraDevice
import kotlinx.coroutines.flow.Flow

/**
 * Where cameras come from. [UsbCameraSource] is the real one; tests use fakes.
 * Devices are identified by a stable string for as long as they stay plugged in
 * (the USB device name, e.g. "/dev/bus/usb/001/004").
 */
interface CameraSource {
    /** UVC devices currently attached, in a stable order. */
    fun attachedDevices(): List<String>

    fun hasPermission(id: String): Boolean

    /** Shows the system permission dialog and suspends until the user answers. */
    suspend fun requestPermission(id: String): Boolean

    /** Opens the device. Blocking; throws on failure. Requires permission. */
    fun open(id: String): CameraDevice

    val attachEvents: Flow<String>
    val detachEvents: Flow<String>
}
