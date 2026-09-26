package dev.borescope.app.camera

import dev.borescope.uvc.CameraDevice
import dev.borescope.uvc.ControlValue
import dev.borescope.uvc.UvcControl
import dev.borescope.uvc.FrameListener
import dev.borescope.uvc.UvcFormat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow

val MJPEG_480 = UvcFormat(UvcFormat.Type.MJPEG, 640, 480, 30, "MJPG")
val MJPEG_720 = UvcFormat(UvcFormat.Type.MJPEG, 1280, 720, 30, "MJPG")
val H264_1080 = UvcFormat(UvcFormat.Type.H264, 1920, 1080, 30, "H264")
val YUYV_480 = UvcFormat(UvcFormat.Type.YUYV, 640, 480, 15, "YUY2")

class FakeDevice(
    override val formats: List<UvcFormat> = listOf(MJPEG_480, MJPEG_720),
    private val startError: Exception? = null,
    /** Formats the camera claims but refuses to stream. */
    var rejects: Set<UvcFormat> = emptySet(),
) : CameraDevice {
    override val diagnostics = "DEVICE CONFIGURATION (fake)"

    /** The camera's controls; [setControl] updates `current`. */
    val controls = linkedMapOf(
        UvcControl.BRIGHTNESS to ControlValue(UvcControl.BRIGHTNESS, -64, 64, 1, 0, 0),
        UvcControl.WHITE_BALANCE_AUTO to ControlValue(UvcControl.WHITE_BALANCE_AUTO, 0, 1, 1, 1, 1),
        UvcControl.WHITE_BALANCE to ControlValue(UvcControl.WHITE_BALANCE, 2800, 6500, 10, 4600, 4600),
    )
    val setCalls = mutableListOf<Pair<UvcControl, Int>>()
    var refusedControls: Set<UvcControl> = emptySet()
    var readControlsError: Exception? = null

    override fun readControls(): List<ControlValue> {
        readControlsError?.let { throw it }
        return controls.values.toList()
    }

    override fun setControl(control: UvcControl, value: Int) {
        setCalls += control to value
        if (control in refusedControls) throw IllegalStateException("Camera refused ${control.label} = $value")
        val c = controls.getValue(control)
        controls[control] = c.copy(current = value.coerceIn(c.min, c.max))
    }
    var streamingFormat: UvcFormat? = null
    var closeCount = 0
    private var listener: FrameListener? = null

    val closed get() = closeCount > 0
    val streaming get() = listener != null

    override fun startStreaming(format: UvcFormat, listener: FrameListener) {
        check(!closed) { "startStreaming after close" }
        startError?.let { throw it }
        check(!streaming) { "startStreaming while streaming" }
        if (format in rejects) throw IllegalStateException("uvc error -51 for $format")
        streamingFormat = format
        this.listener = listener
    }

    override fun stopStreaming() {
        listener = null
    }

    override fun close() {
        stopStreaming()
        closeCount++
    }

    fun emitFrame() {
        checkNotNull(listener) { "not streaming" }.onFrame(byteArrayOf(1, 2, 3), 3, 1280, 720, 0)
    }
}

class FakeSource : CameraSource {
    val devices = mutableListOf<String>()
    val permitted = mutableSetOf<String>()

    /** Answer given to permission requests; null means the dialog stays up until [pendingPermission] completes. */
    var permissionAnswer: Boolean? = true
    var pendingPermission = CompletableDeferred<Boolean>()
    var permissionRequests = 0

    var openError: Exception? = null
    var nextDevice: () -> FakeDevice = { FakeDevice() }
    val opened = mutableListOf<FakeDevice>()

    override val attachEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)
    override val detachEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)

    fun plugIn(id: String, permission: Boolean) {
        devices += id
        if (permission) permitted += id
    }

    fun unplug(id: String) {
        devices -= id
        permitted -= id
    }

    override fun attachedDevices(): List<String> = devices.toList()

    override fun hasPermission(id: String) = id in permitted

    override suspend fun requestPermission(id: String): Boolean {
        permissionRequests++
        val granted = permissionAnswer ?: pendingPermission.await()
        if (granted) permitted += id
        return granted
    }

    override fun open(id: String): FakeDevice {
        check(id in permitted) { "open without permission" }
        openError?.let { throw it }
        return nextDevice().also { opened += it }
    }
}
