package dev.borescope.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
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

/**
 * The live picture: fitted to the screen at any rotation, mirrorable,
 * pinch-to-zoom and drag to pan, double-tap to reset. View-only: photos and
 * videos always contain the full frame.
 */
@Composable
fun Viewport(frame: Bitmap, rotation: Float, mirrored: Boolean, modifier: Modifier = Modifier) {
    var zoom by rememberSaveable { mutableFloatStateOf(1f) }
    var panX by rememberSaveable { mutableFloatStateOf(0f) }
    var panY by rememberSaveable { mutableFloatStateOf(0f) }
    var box by rememberSaveable(stateSaver = IntSizeSaver) { mutableStateOf(IntSize.Zero) }

    Box(
        modifier
            .fillMaxSize()
            .onSizeChanged { box = it }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, gestureZoom, _ ->
                    zoom = (zoom * gestureZoom).coerceIn(1f, MAX_ZOOM)
                    val clamped = clampPan(Offset(panX, panY) + pan, zoom, box)
                    panX = clamped.x
                    panY = clamped.y
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    zoom = 1f
                    panX = 0f
                    panY = 0f
                })
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
                    scaleX = zoom * if (mirrored) -1f else 1f
                    scaleY = zoom
                    rotationZ = rotation
                    translationX = panX
                    translationY = panY
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

private val IntSizeSaver = androidx.compose.runtime.saveable.Saver<IntSize, Long>(
    save = { (it.width.toLong() shl 32) or (it.height.toLong() and 0xFFFFFFFF) },
    restore = { IntSize((it shr 32).toInt(), it.toInt()) },
)
