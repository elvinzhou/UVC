package dev.borescope.app

import android.app.Application
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.borescope.app.camera.CameraController
import dev.borescope.app.camera.CameraState
import dev.borescope.app.camera.UsbCameraSource
import dev.borescope.app.capture.MjpegFrames
import dev.borescope.app.capture.PhotoSaver
import dev.borescope.app.capture.VideoRecorder
import dev.borescope.app.frame.FrameDecoder
import dev.borescope.uvc.UvcFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

class ViewerViewModel(app: Application) : AndroidViewModel(app) {

    /** Every camera operation runs here, never on the main thread. */
    private val cameraThread = Executors.newSingleThreadExecutor { Thread(it, "camera") }
        .asCoroutineDispatcher()

    private val decoder = FrameDecoder()
    private val controller = CameraController(UsbCameraSource(app), cameraThread, ::onFrame)

    val state: StateFlow<CameraState> = controller.state

    private val _frame = MutableStateFlow<Bitmap?>(null)
    val frame: StateFlow<Bitmap?> = _frame.asStateFlow()

    val rotation = MutableStateFlow(0f)
    val mirrored = MutableStateFlow(false)
    val showInfo = MutableStateFlow(false)

    /** Set by [takePhoto]; the next frame is saved (frame buffers are reused, so we can't look back). */
    @Volatile private var photoRequested = false

    /** Active recording; frames are drawn into it from the native frame thread. */
    @Volatile private var recorder: VideoRecorder? = null
    private val recordingMutex = Mutex()
    private val _recordingSince = MutableStateFlow<Long?>(null)
    /** [SystemClock.elapsedRealtime] when the current recording started, or null. */
    val recordingSince: StateFlow<Long?> = _recordingSince.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** One-off user notices (saved photo, saved video, errors). */
    val messages: SharedFlow<String> = _messages

    init {
        // Never leave the last picture of a closed stream on screen as if it were live.
        // Closing joins the frame thread first, so no frame can arrive after this.
        viewModelScope.launch {
            state.collect {
                if (it !is CameraState.Streaming) {
                    _frame.value = null
                    photoRequested = false
                    // The stream ended (unplug, background, error): finish the file.
                    if (recorder != null) launch(Dispatchers.IO) { recordingMutex.withLock { stopRecording() } }
                }
            }
        }
        viewModelScope.launch { controller.notices.collect { _messages.tryEmit(it) } }
    }

    fun start() = controller.start()
    fun stop() = controller.stop()
    fun retry() = controller.retry()
    fun onUsbDeviceAttached() = controller.deviceAttached()
    fun selectFormat(format: UvcFormat) = controller.selectFormat(format)

    // Native frame thread. Decoding here is fine: libuvc drops frames rather
    // than queueing them if we fall behind. [data] is only valid during the call.
    private fun onFrame(data: ByteArray, length: Int, width: Int, height: Int, type: Int) {
        val bitmap = decoder.decode(data, length, width, height, type) ?: return
        if (photoRequested) {
            photoRequested = false
            capturePhoto(data, length, type, bitmap)
        }
        _frame.value = bitmap
        recorder?.drawFrame(bitmap)
    }

    /** Saves the next frame as seen on screen (rotation and mirroring via EXIF, pixels untouched). */
    fun takePhoto() {
        if (state.value is CameraState.Streaming) photoRequested = true
    }

    // Frame thread: copy what we need now, encode and write on IO.
    private fun capturePhoto(data: ByteArray, length: Int, type: Int, bitmap: Bitmap) {
        val orientation = MjpegFrames.exifOrientation(rotation.value.toInt(), mirrored.value)
        val mjpeg = type == UvcFormat.Type.MJPEG.ordinal
        // MJPEG: the camera's own JPEG, byte for byte (plus missing tables + EXIF).
        val jpeg = if (mjpeg) MjpegFrames.toJpegFile(data, length, orientation) else null
        val copy = if (mjpeg) null else bitmap.copy(Bitmap.Config.ARGB_8888, false)
        viewModelScope.launch(Dispatchers.IO) {
            val bytes = jpeg ?: copy?.let {
                val encoded = ByteArrayOutputStream().also { out -> it.compress(Bitmap.CompressFormat.JPEG, 95, out) }
                MjpegFrames.toJpegFile(encoded.toByteArray(), exifOrientation = orientation)
            }
            val saved = bytes?.let { PhotoSaver.save(getApplication(), it) }
            _messages.tryEmit(if (saved != null) "Photo saved to Pictures/Borescope" else "Couldn't save the photo")
        }
    }

    fun toggleRecording() {
        viewModelScope.launch(Dispatchers.IO) {
            recordingMutex.withLock { if (recorder == null) startRecording() else stopRecording() }
        }
    }

    private fun startRecording() {
        val streaming = state.value as? CameraState.Streaming ?: return
        val format = streaming.format
        try {
            recorder = VideoRecorder.start(
                getApplication(), format.width, format.height, format.fps,
                rotationDegrees = rotation.value.toInt(), mirrored = mirrored.value,
            )
            _recordingSince.value = SystemClock.elapsedRealtime()
        } catch (e: Exception) {
            _messages.tryEmit("Couldn't start recording: ${e.message ?: e}")
        }
    }

    private fun stopRecording() {
        val active = recorder ?: return
        recorder = null
        _recordingSince.value = null
        val uri = active.stop()
        _messages.tryEmit(if (uri != null) "Video saved to Movies/Borescope" else "Recording was empty; nothing saved")
    }

    fun rotate() { rotation.value = (rotation.value + 90f) % 360f }

    fun toggleMirror() { mirrored.value = !mirrored.value }

    fun toggleInfo() { showInfo.value = !showInfo.value }

    override fun onCleared() {
        // viewModelScope is already cancelled; finish any recording on a plain thread.
        recorder?.let { active ->
            recorder = null
            Thread({ active.stop() }, "video-finish").start()
        }
        // Closing the camera may block; it happens on the camera thread, which
        // is released once the controller has finished.
        controller.shutdown().invokeOnCompletion { cameraThread.close() }
    }
}
