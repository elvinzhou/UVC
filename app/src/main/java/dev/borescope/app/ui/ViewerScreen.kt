package dev.borescope.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.RotateRight
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.borescope.app.CameraState
import dev.borescope.app.ViewerViewModel

@Composable
fun ViewerScreen(vm: ViewerViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val frame by vm.frame.collectAsStateWithLifecycle()
    val rotation by vm.rotation.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        frame?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = "Borescope view",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer { rotationZ = rotation },
            )
        }

        Text(
            text = when (val s = state) {
                CameraState.NoDevice -> "Plug in a USB borescope"
                CameraState.PermissionDenied -> "USB permission denied"
                is CameraState.Streaming -> s.format.toString()
                is CameraState.Error -> "Error: ${s.message}"
            },
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(12.dp),
        )

        Row(
            horizontalArrangement = Arrangement.SpaceEvenly,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .navigationBarsPadding().padding(24.dp),
        ) {
            FilledIconButton(onClick = vm::rotate) {
                Icon(Icons.Filled.RotateRight, contentDescription = "Rotate")
            }
            FilledIconButton(onClick = vm::takePhoto, enabled = state is CameraState.Streaming) {
                Icon(Icons.Filled.PhotoCamera, contentDescription = "Take photo")
            }
            // TODO: record button once capture/VideoRecorder is implemented
        }
    }
}
