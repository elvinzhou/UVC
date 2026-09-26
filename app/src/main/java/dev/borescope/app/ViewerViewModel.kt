package dev.borescope.app

import android.app.Application
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.borescope.app.camera.CameraController
import dev.borescope.app.camera.CameraState
import dev.borescope.app.camera.UsbCameraSource
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
    val showInfo = MutableStateFlow(false)

    /** The last frame's JPEG bytes when streaming MJPEG, for lossless photos. */
    @Volatile private var lastJpeg: ByteArray? = null

    /** Active recording; frames are drawn into it from the native frame thread. */
    @Volatile private var recorder: VideoRecorder? = null
    private val recordingMutex = Mutex()
    private val _recordingSince = MutableStateFlow<Long?>(null)
    /** [SystemClock.elapsedRealtime] when the current recording started, or null. */
    val recordingSince: StateFlow<Long?> = _recordingSince.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** One-off user notices (saved photo, saved video, errors). */
    val messages: SharedFlow<String> = _messages

    init {
        // Never leave the last picture of a closed stream on screen as if it were live.
        // Closing joins the frame thread first, so no frame can arrive after this.
        viewModelScope.launch {
            state.collect {
                if (it !is CameraState.Streaming) {
                    _frame.value = null
                    lastJpeg = null
                    // The stream ended (unplug, background, error): finish the file.
                    if (recorder != null) launch(Dispatchers.IO) { recordingMutex.withLock { stopRecording() } }
                }
            }
        }
    }

    fun start() = controller.start()
    fun stop() = controller.stop()
    fun retry() = controller.retry()
    fun onUsbDeviceAttached() = controller.deviceAttached()

    // Native frame thread. Decoding here is fine: libuvc drops frames rather
    // than queueing them if we fall behind.
    private fun onFrame(data: ByteArray, width: Int, height: Int, type: Int) {
        val bitmap = decoder.decode(data, width, height, type) ?: return
        lastJpeg = if (type == UvcFormat.Type.MJPEG.ordinal) data else null
        _frame.value = bitmap
        recorder?.drawFrame(bitmap)
        // TODO(phase 4): reuse bitmaps via BitmapFactory.Options.inBitmap to cut GC churn
    }

    /** MJPEG frames are saved verbatim (zero quality loss); YUYV frames are encoded once. */
    fun takePhoto() {
        val jpeg = lastJpeg
        val bitmap = _frame.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val bytes = jpeg ?: ByteArrayOutputStream().also {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)
            }.toByteArray()
            val saved = PhotoSaver.save(getApplication(), bytes)
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
                rotationDegrees = rotation.value.toInt(), mirrored = false,
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
