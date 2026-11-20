package dev.borescope.app.ui

import dev.borescope.app.camera.CameraState
import dev.borescope.uvc.UvcFormat

/** One selectable stream mode for the resolution picker. */
data class ResolutionOption(val format: UvcFormat, val label: String)

/**
 * The modes the app can display, largest first, then highest frame rate; MJPEG before raw YUYV
 * when sizes and rates tie. Modes we can't decode (H.264, other raw layouts) are left out.
 */
fun resolutionOptions(formats: List<UvcFormat>): List<ResolutionOption> =
    formats.filter { it.isDisplayable }
        .distinct()
        .sortedWith(
            compareByDescending<UvcFormat> { it.width * it.height }
                .thenByDescending { it.fps }
                .thenBy { it.type != UvcFormat.Type.MJPEG },
        )
        .map {
            val raw = if (it.type == UvcFormat.Type.YUYV) " · raw" else ""
            ResolutionOption(it, "${it.width}×${it.height} · ${it.fps} fps$raw")
        }

/** What to tell the user in a panel when there is no open camera. */
fun noCameraHint(state: CameraState): String = when (state) {
    CameraState.Connecting -> "Connecting to the camera…"
    CameraState.NeedsPermission -> "Grant the camera permission to see its settings."
    is CameraState.Error -> "The camera isn't available right now."
    else -> "No camera connected. Plug in a USB borescope to choose its resolution and adjust the image."
}
