package dev.borescope.app.camera

import android.content.Context
import android.hardware.usb.UsbDevice
import dev.borescope.uvc.CameraDevice
import dev.borescope.uvc.UsbCameraManager
import dev.borescope.uvc.UvcCamera
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** [CameraSource] backed by Android's UsbManager and libuvc. */
class UsbCameraSource(context: Context) : CameraSource {
    private val usb = UsbCameraManager(context.applicationContext)

    override fun attachedDevices(): List<String> = usb.uvcDevices().map { it.deviceName }.sorted()

    override fun hasPermission(id: String): Boolean = device(id)?.let(usb::hasPermission) ?: false

    override suspend fun requestPermission(id: String): Boolean =
        device(id)?.let { usb.requestPermission(it) } ?: false

    override fun open(id: String): CameraDevice =
        UvcCamera.open(usb.usbManager, device(id) ?: error("Camera was unplugged"))

    override val attachEvents: Flow<String> = usb.attachEvents().map { it.deviceName }
    override val detachEvents: Flow<String> = usb.detachEvents().map { it.deviceName }

    // UsbManager.deviceList is keyed by device name.
    private fun device(id: String): UsbDevice? = usb.usbManager.deviceList[id]
}
