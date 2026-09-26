package dev.borescope.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import android.os.SystemClock
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.borescope.app.ViewerViewModel
import dev.borescope.app.camera.CameraState
import dev.borescope.uvc.UvcFormat

@Composable
fun ViewerScreen(vm: ViewerViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val frame by vm.frame.collectAsStateWithLifecycle()
    val rotation by vm.rotation.collectAsStateWithLifecycle()
    val mirrored by vm.mirrored.collectAsStateWithLifecycle()
    val showInfo by vm.showInfo.collectAsStateWithLifecycle()
    val showControls by vm.showControls.collectAsStateWithLifecycle()
    val recordingSince by vm.recordingSince.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        frame?.let { Viewport(it, rotation, mirrored) }

        StatusMessage(state, onRetry = vm::retry, modifier = Modifier.align(Alignment.Center))

        (state as? CameraState.Streaming)?.let {
            Text(
                text = it.format.toString(),
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(12.dp),
            )
        }

        if (showControls) {
            ControlsPanel(
                controls = (state as? CameraState.Streaming)?.controls.orEmpty(),
                onChange = vm::setControl,
                onReset = vm::resetControls,
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 36.dp),
            )
        }

        recordingSince?.let { since ->
            RecordingIndicator(since, Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp))
        }

        if (showInfo) {
            InfoPanel(
                state,
                onSelect = vm::selectFormat,
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 36.dp),
            )
        }

        Row(
            horizontalArrangement = Arrangement.SpaceEvenly,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .navigationBarsPadding().padding(24.dp),
        ) {
            FilledIconButton(onClick = vm::rotate) {
                Icon(Icons.AutoMirrored.Filled.RotateRight, contentDescription = "Rotate")
            }
            FilledIconToggleButton(checked = mirrored, onCheckedChange = { vm.toggleMirror() }) {
                Icon(Icons.Filled.Flip, contentDescription = "Mirror")
            }
            FilledIconButton(onClick = vm::takePhoto, enabled = frame != null) {
                Icon(Icons.Filled.PhotoCamera, contentDescription = "Take photo")
            }
            val recording = recordingSince != null
            FilledIconButton(
                onClick = vm::toggleRecording,
                enabled = recording || state is CameraState.Streaming,
                colors = if (recording) {
                    IconButtonDefaults.filledIconButtonColors(containerColor = Color.Red, contentColor = Color.White)
                } else {
                    IconButtonDefaults.filledIconButtonColors()
                },
            ) {
                if (recording) {
                    Icon(Icons.Filled.Stop, contentDescription = "Stop recording")
                } else {
                    Icon(Icons.Filled.FiberManualRecord, contentDescription = "Record video", tint = Color.Red)
                }
            }
            FilledIconToggleButton(checked = showControls, onCheckedChange = { vm.toggleControls() }) {
                Icon(Icons.Filled.Tune, contentDescription = "Camera controls")
            }
            FilledIconToggleButton(checked = showInfo, onCheckedChange = { vm.toggleInfo() }) {
                Icon(Icons.Filled.Info, contentDescription = "Camera details")
            }
        }

        SnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 88.dp),
        )
    }
}

@Composable
private fun RecordingIndicator(since: Long, modifier: Modifier) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(since) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(250)
        }
    }
    Text(
        text = "● REC " + formatElapsed(now - since),
        color = Color.Red,
        style = MaterialTheme.typography.labelLarge,
        modifier = modifier,
    )
}

/** "m:ss" or "h:mm:ss". */
internal fun formatElapsed(millis: Long): String {
    val total = (millis.coerceAtLeast(0) / 1000)
    val h = total / 3600
    val m = (total / 60) % 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

@Composable
private fun StatusMessage(state: CameraState, onRetry: () -> Unit, modifier: Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val text = when (state) {
            CameraState.Idle, is CameraState.Streaming -> return@Column
            CameraState.NoDevice -> "Plug in a USB borescope"
            CameraState.Connecting -> "Connecting…"
            CameraState.NeedsPermission -> "Borescope needs permission to use the camera"
            is CameraState.Error -> state.message
        }
        if (state == CameraState.Connecting) CircularProgressIndicator()
        Text(text, color = Color.White, textAlign = TextAlign.Center)
        when (state) {
            CameraState.NeedsPermission -> Button(onClick = onRetry) { Text("Grant USB permission") }
            is CameraState.Error -> Button(onClick = onRetry) { Text("Retry") }
            else -> Unit
        }
    }
}

/** What the camera advertises, straight from its descriptors. Tap a format to switch to it. */
@Composable
private fun InfoPanel(state: CameraState, onSelect: (UvcFormat) -> Unit, modifier: Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .heightIn(max = 480.dp)
            .padding(horizontal = 12.dp)
            .background(Color.Black.copy(alpha = 0.8f), MaterialTheme.shapes.medium)
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val streaming = state as? CameraState.Streaming
        if (streaming == null) {
            Text("No camera open", color = Color.White)
            return@Column
        }
        Text("Formats (tap to switch)", color = Color.White, fontWeight = FontWeight.Bold)
        streaming.formats.forEach { f ->
            val active = f == streaming.format
            Text(
                text = (if (active) "▶ " else "   ") + "$f  ${f.fourcc}" + if (f.isDisplayable) "" else "  (unsupported)",
                color = when {
                    active -> MaterialTheme.colorScheme.primary
                    f.isDisplayable -> Color.White
                    else -> Color.Gray
                },
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = f.isDisplayable && !active) { onSelect(f) }
                    .padding(vertical = 6.dp),
            )
        }
        if (streaming.extensionUnits.isNotEmpty()) {
            Text("Extension units (vendor)", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            streaming.extensionUnits.forEach {
                Text(it.toString(), color = Color.White, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }
        Text("Descriptors", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
        Text(
            streaming.diagnostics,
            color = Color.White,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
