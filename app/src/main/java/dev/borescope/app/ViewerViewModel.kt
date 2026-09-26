package dev.borescope.app

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.borescope.app.capture.PhotoSaver
import dev.borescope.uvc.UsbCameraManager
import dev.borescope.uvc.UvcCamera
import dev.borescope.uvc.UvcFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface CameraState {
    data object NoDevice : CameraState
    data object PermissionDenied : CameraState
    data class Streaming(val format: UvcFormat) : CameraState
    data class Error(val message: String) : CameraState
}

class ViewerViewModel(app: Application) : AndroidViewModel(app) {
    private val usb = UsbCameraManager(app)
    private var camera: UvcCamera? = null

    private val _state = MutableStateFlow<CameraState>(CameraState.NoDevice)
    val state: StateFlow<CameraState> = _state.asStateFlow()

    private val _frame = MutableStateFlow<Bitmap?>(null)
    val frame: StateFlow<Bitmap?> = _frame.asStateFlow()

    val rotation = MutableStateFlow(0f)

    @Volatile private var lastJpeg: ByteArray? = null

    init {
        viewModelScope.launch {
            usb.detachEvents().collect {
                disconnect()
                _state.value = CameraState.NoDevice
            }
        }
    }

    fun connect() {
        if (camera != null) return
        viewModelScope.launch {
            val device = usb.uvcDevices().firstOrNull()
                ?: run { _state.value = CameraState.NoDevice; return@launch }
            if (!usb.requestPermission(device)) {
                _state.value = CameraState.PermissionDenied
                return@launch
            }
            runCatching {
                withContext(Dispatchers.IO) {
                    val cam = UvcCamera.open(usb.usbManager, device)
                    val format = cam.preferredFormat() ?: error("Camera reports no formats")
                    cam.startStreaming(format, ::onFrame)
                    camera = cam
                    format
                }
            }.onSuccess { _state.value = CameraState.Streaming(it) }
             .onFailure { _state.value = CameraState.Error(it.message ?: it.toString()) }
        }
    }

    fun disconnect() {
        camera?.close()
        camera = null
    }

    // Native streaming thread. Decoding here is fine for a first milestone:
    // libuvc drops frames rather than queueing if we fall behind.
    private fun onFrame(data: ByteArray, width: Int, height: Int, type: Int) {
        if (type != UvcFormat.Type.MJPEG.ordinal) return  // TODO: YUYV -> Bitmap
        lastJpeg = data
        BitmapFactory.decodeByteArray(data, 0, data.size)?.let { _frame.value = it }
        // TODO: reuse bitmaps via BitmapFactory.Options.inBitmap to cut GC churn
    }

    /** MJPEG frames are already JPEGs: save the bytes verbatim, zero quality loss. */
    fun takePhoto() {
        val jpeg = lastJpeg ?: return
        viewModelScope.launch(Dispatchers.IO) { PhotoSaver.save(getApplication(), jpeg) }
    }

    fun rotate() { rotation.value = (rotation.value + 90f) % 360f }

    override fun onCleared() = disconnect()
}
