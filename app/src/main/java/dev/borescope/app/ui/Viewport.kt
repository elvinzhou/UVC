package dev.borescope.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize

const val MAX_ZOOM = 8f

/** Zoom and pan of the live view, hoisted so other UI (zoom buttons, hints) can read and drive it. */
@Stable
class ViewportState(zoom: Float = 1f, panX: Float = 0f, panY: Float = 0f) {
    var zoom by mutableFloatStateOf(zoom)
        private set
    var panX by mutableFloatStateOf(panX)
        private set
    var panY by mutableFloatStateOf(panY)
        private set
    /** Size of the view, set by [Viewport] so panning can be clamped. */
    var box: IntSize = IntSize.Zero

    val isZoomed: Boolean get() = zoom > 1.001f

    fun zoomBy(factor: Float) {
        zoom = (zoom * factor).coerceIn(1f, MAX_ZOOM)
        setPan(Offset(panX, panY))
    }

    fun panBy(delta: Offset) = setPan(Offset(panX, panY) + delta)

    fun reset() {
        zoom = 1f
        panX = 0f
        panY = 0f
    }

    private fun setPan(p: Offset) {
        val c = clampPan(p, zoom, box)
        panX = c.x
        panY = c.y
    }

    companion object {
        val Saver = listSaver<ViewportState, Float>(
            save = { listOf(it.zoom, it.panX, it.panY) },
            restore = { ViewportState(it[0], it[1], it[2]) },
        )
    }
}

/**
 * The live picture: fitted to the screen at any rotation, mirrorable,
 * pinch-to-zoom and drag to pan, double-tap to reset. View-only: photos and
 * videos always contain the full frame.
 */
@Composable
fun Viewport(
    frame: Bitmap,
    rotation: Float,
    mirrored: Boolean,
    state: ViewportState,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxSize()
            .onSizeChanged { state.box = it }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, gestureZoom, _ ->
                    state.zoomBy(gestureZoom)
                    state.panBy(pan)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { state.reset() })
            },
    ) {
        Image(
            bitmap = frame.asImageBitmap(),
            contentDescription = "Borescope view",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fitRotated(rotation)
                .graphicsLayer {
                    // Applied as: mirror, rotate, zoom (about the center), then pan in
                    // screen space. Photos' EXIF and videos use the same order.
                    scaleX = state.zoom * if (mirrored) -1f else 1f
                    scaleY = state.zoom
                    rotationZ = rotation
                    translationX = state.panX
                    translationY = state.panY
                },
        )
    }
}

/**
 * Lays the child out in a box with swapped width/height when it'll be shown
 * rotated a quarter turn, so ContentScale.Fit fits the *rotated* picture and
 * nothing is clipped.
 */
private fun Modifier.fitRotated(degrees: Float): Modifier = layout { measurable, constraints ->
    val quarterTurn = ((degrees / 90f).toInt() % 2) != 0
    val w = constraints.maxWidth
    val h = constraints.maxHeight
    val child = measurable.measure(if (quarterTurn) Constraints.fixed(h, w) else Constraints.fixed(w, h))
    layout(w, h) { child.place((w - child.width) / 2, (h - child.height) / 2) }
}

/** Keeps a zoomed picture covering the screen: at most half the extra size in each direction. */
internal fun clampPan(pan: Offset, zoom: Float, box: IntSize): Offset {
    if (zoom <= 1f) return Offset.Zero
    val maxX = box.width * (zoom - 1f) / 2f
    val maxY = box.height * (zoom - 1f) / 2f
    return Offset(pan.x.coerceIn(-maxX, maxX), pan.y.coerceIn(-maxY, maxY))
}
