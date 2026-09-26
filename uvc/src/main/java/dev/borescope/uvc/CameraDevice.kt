package dev.borescope.uvc

/**
 * An open camera. [UvcCamera] is the real implementation; tests use fakes.
 * Implementations are not thread-safe: call every method from one thread.
 * Every method may block (USB I/O, joining native threads), so never call
 * them on the main thread.
 */
interface CameraDevice : AutoCloseable {
    /** All stream modes the camera advertises. */
    val formats: List<UvcFormat>

    /** Human-readable dump of the camera's USB video descriptors. */
    val diagnostics: String

    /** Throws if the camera rejects [format]. */
    fun startStreaming(format: UvcFormat, listener: FrameListener)

    /** Returns once no more frames will be delivered. Safe to call when not streaming. */
    fun stopStreaming()

    /**
     * Reads the standard image controls the camera supports, with their ranges
     * and current values. Controls the camera advertises but won't answer for
     * are left out. Each value costs a USB round trip.
     */
    fun readControls(): List<ControlValue> = emptyList()

    /** Sets a control; throws if the camera refuses. */
    fun setControl(control: UvcControl, value: Int): Unit =
        throw UnsupportedOperationException("Controls not supported")

    /** Vendor extension units, for device-specific features such as LEDs. */
    val extensionUnits: List<ExtensionUnit> get() = emptyList()

    /** Raw GET_CUR of an extension unit control, or null if it fails. */
    fun readExtensionControl(unitId: Int, selector: Int): ByteArray? = null

    /** Raw SET_CUR of an extension unit control; throws if the camera refuses. */
    fun writeExtensionControl(unitId: Int, selector: Int, data: ByteArray): Unit =
        throw UnsupportedOperationException("Extension units not supported")

    /** Stops streaming if needed and releases the device. Safe to call twice. */
    override fun close()
}
