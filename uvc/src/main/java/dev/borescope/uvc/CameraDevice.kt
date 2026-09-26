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

    /** Stops streaming if needed and releases the device. Safe to call twice. */
    override fun close()
}
