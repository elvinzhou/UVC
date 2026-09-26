package dev.borescope.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.borescope.app.ViewerViewModel
import dev.borescope.app.camera.CameraState

@Composable
fun ViewerScreen(vm: ViewerViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val frame by vm.frame.collectAsStateWithLifecycle()
    val rotation by vm.rotation.collectAsStateWithLifecycle()
    val showInfo by vm.showInfo.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        frame?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = "Borescope view",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer { rotationZ = rotation },
            )
        }

        StatusMessage(state, onRetry = vm::retry, modifier = Modifier.align(Alignment.Center))

        (state as? CameraState.Streaming)?.let {
            Text(
                text = it.format.toString(),
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(12.dp),
            )
        }

        if (showInfo) {
            InfoPanel(state, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 36.dp))
        }

        Row(
            horizontalArrangement = Arrangement.SpaceEvenly,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .navigationBarsPadding().padding(24.dp),
        ) {
            FilledIconButton(onClick = vm::rotate) {
                Icon(Icons.AutoMirrored.Filled.RotateRight, contentDescription = "Rotate")
            }
            FilledIconButton(onClick = vm::takePhoto, enabled = frame != null) {
                Icon(Icons.Filled.PhotoCamera, contentDescription = "Take photo")
            }
            FilledIconButton(onClick = vm::toggleInfo) {
                Icon(Icons.Filled.Info, contentDescription = "Camera details")
            }
            // TODO(phase 3): record button once capture/VideoRecorder is implemented
        }
    }
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

/** Phase 1 debugging aid: what the camera advertises, straight from its descriptors. */
@Composable
private fun InfoPanel(state: CameraState, modifier: Modifier) {
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
        Text("Formats", color = Color.White, fontWeight = FontWeight.Bold)
        streaming.formats.forEach { f ->
            val active = f == streaming.format
            Text(
                text = (if (active) "▶ " else "   ") + "$f  ${f.fourcc}" + if (f.isDisplayable) "" else "  (unsupported)",
                color = if (active) MaterialTheme.colorScheme.primary else Color.White,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
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
