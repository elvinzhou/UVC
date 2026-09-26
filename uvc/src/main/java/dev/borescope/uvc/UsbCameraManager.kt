package dev.borescope.uvc

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Finds UVC devices, requests permission, and reports attach/detach. */
class UsbCameraManager(private val context: Context) {

    val usbManager: UsbManager = context.getSystemService(UsbManager::class.java)

    fun uvcDevices(): List<UsbDevice> = usbManager.deviceList.values.filter(::isUvc)

    fun hasPermission(device: UsbDevice) = usbManager.hasPermission(device)

    /**
     * Asks the user for permission. Not needed when the app was launched by the
     * USB_DEVICE_ATTACHED intent filter and the user accepted that dialog.
     */
    suspend fun requestPermission(device: UsbDevice): Boolean {
        if (usbManager.hasPermission(device)) return true
        return suspendCancellableCoroutine { cont ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    context.unregisterReceiver(this)
                    if (cont.isActive) {
                        cont.resume(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                    }
                }
            }
            ContextCompat.registerReceiver(
                context, receiver, IntentFilter(ACTION_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }

            // Explicit (package-scoped) intent; must be MUTABLE so the system can add extras.
            val intent = Intent(ACTION_PERMISSION).setPackage(context.packageName)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            usbManager.requestPermission(device, PendingIntent.getBroadcast(context, 0, intent, flags))
        }
    }

    /** Emits the device whenever a UVC device is plugged in. */
    fun attachEvents(): Flow<UsbDevice> = deviceEvents(UsbManager.ACTION_USB_DEVICE_ATTACHED)

    /** Emits the device whenever a UVC device is unplugged. */
    fun detachEvents(): Flow<UsbDevice> = deviceEvents(UsbManager.ACTION_USB_DEVICE_DETACHED)

    private fun deviceEvents(action: String): Flow<UsbDevice> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val device = deviceFrom(intent)
                if (device != null && isUvc(device)) trySend(device)
            }
        }
        // System broadcast, so it must be registered as exported.
        ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_EXPORTED)
        awaitClose { context.unregisterReceiver(receiver) }
    }

    companion object {
        private const val ACTION_PERMISSION = "dev.borescope.uvc.USB_PERMISSION"

        /** The [UsbManager.EXTRA_DEVICE] of a USB intent or broadcast, if any. */
        fun deviceFrom(intent: Intent): UsbDevice? =
            if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }

        fun isUvc(device: UsbDevice): Boolean =
            (0 until device.interfaceCount).any {
                device.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_VIDEO
            }
    }
}
