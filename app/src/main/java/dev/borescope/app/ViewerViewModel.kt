package dev.borescope.app

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.borescope.app.camera.CameraController
import dev.borescope.app.camera.CameraState
import dev.borescope.app.camera.UsbCameraSource
import dev.borescope.app.capture.PhotoSaver
import dev.borescope.app.frame.FrameDecoder
import dev.borescope.uvc.UvcFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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

    init {
        // Never leave the last picture of a closed stream on screen as if it were live.
        // Closing joins the frame thread first, so no frame can arrive after this.
        viewModelScope.launch {
            state.collect {
                if (it !is CameraState.Streaming) {
                    _frame.value = null
                    lastJpeg = null
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
            PhotoSaver.save(getApplication(), bytes)
        }
    }

    fun rotate() { rotation.value = (rotation.value + 90f) % 360f }

    fun toggleInfo() { showInfo.value = !showInfo.value }

    override fun onCleared() {
        // Closing the camera may block; it happens on the camera thread, which
        // is released once the controller has finished.
        controller.shutdown().invokeOnCompletion { cameraThread.close() }
    }
}
