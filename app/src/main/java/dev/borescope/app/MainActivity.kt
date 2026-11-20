package dev.borescope.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.core.content.ContextCompat
import dev.borescope.app.ui.ViewerScreen

class MainActivity : ComponentActivity() {
    private val viewModel: ViewerViewModel by viewModels()

    // Android grants USB access to video-class devices only to apps holding CAMERA.
    // Without it the USB dialog never appears and the controller lands on NeedsPermission.
    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) viewModel.retry()
        }

    /** Ask for CAMERA once per launch on our own; after that only the button asks. */
    private var askedForCamera = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        askedForCamera = savedInstanceState?.getBoolean(KEY_ASKED_CAMERA) ?: false
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                ViewerScreen(viewModel, onRetry = ::retry)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.start()
        if (!hasCameraPermission() && !askedForCamera) {
            askedForCamera = true
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onStop() {
        viewModel.stop()   // release the camera when backgrounded
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_ASKED_CAMERA, askedForCamera)
    }

    // singleTop: plugging the scope in while we're open lands here, with USB
    // permission already granted by the system's "open with Borescope?" dialog
    // (which only counts once we also hold CAMERA).
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) viewModel.onUsbDeviceAttached()
    }

    /** The status screen's button: get CAMERA first if we lack it, then retry USB. */
    private fun retry() {
        if (hasCameraPermission()) viewModel.retry()
        else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private companion object {
        const val KEY_ASKED_CAMERA = "asked_camera_permission"
    }
}
