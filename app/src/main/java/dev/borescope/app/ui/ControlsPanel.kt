package dev.borescope.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.borescope.uvc.ControlValue
import dev.borescope.uvc.UvcControl
import kotlin.math.roundToInt

/** The camera's own image controls (brightness, white balance, ...), as it reports them. */
@Composable
fun ControlsPanel(
    controls: List<ControlValue>,
    onChange: (UvcControl, Int) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .heightIn(max = 420.dp)
            .padding(horizontal = 12.dp)
            .background(Color.Black.copy(alpha = 0.8f), MaterialTheme.shapes.medium)
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Camera controls", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (controls.isNotEmpty()) TextButton(onClick = onReset) { Text("Reset") }
        }
        if (controls.isEmpty()) {
            Text("This camera doesn't report any adjustable controls.", color = Color.White)
            return@Column
        }
        val byControl = controls.associateBy { it.control }
        for (c in controls) {
            val lockedByAuto = c.control.autoSwitch?.let { byControl[UvcControl.valueOf(it)]?.current == 1 } == true
            when (c.control.kind) {
                UvcControl.Kind.TOGGLE -> ToggleRow(c, onChange)
                UvcControl.Kind.MENU -> MenuRow(c, onChange)
                UvcControl.Kind.RANGE -> RangeRow(c, enabled = !lockedByAuto, onChange)
            }
        }
    }
}

@Composable
private fun ToggleRow(c: ControlValue, onChange: (UvcControl, Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(c.control.label, color = Color.White, modifier = Modifier.weight(1f))
        Switch(checked = c.current != 0, onCheckedChange = { onChange(c.control, if (it) 1 else 0) })
    }
}

@Composable
private fun MenuRow(c: ControlValue, onChange: (UvcControl, Int) -> Unit) {
    Column {
        Text(c.control.label, color = Color.White)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            UvcControl.POWER_LINE_OPTIONS.filter { (v, _) -> v in c.min..c.max }.forEach { (v, label) ->
                FilterChip(selected = c.current == v, onClick = { onChange(c.control, v) }, label = { Text(label) })
            }
        }
    }
}

@Composable
private fun RangeRow(c: ControlValue, enabled: Boolean, onChange: (UvcControl, Int) -> Unit) {
    // While dragging, show the finger's value; otherwise what the camera reports.
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(c.current.toFloat()) }
    val shown = if (dragging) dragValue else c.current.toFloat()
    Column {
        Row {
            Text(c.control.label, color = if (enabled) Color.White else Color.Gray, modifier = Modifier.weight(1f))
            Text(if (enabled) shown.roundToInt().toString() else "auto", color = Color.White)
        }
        Slider(
            value = shown,
            valueRange = c.min.toFloat()..c.max.toFloat(),
            enabled = enabled,
            onValueChange = {
                dragging = true
                dragValue = it
                onChange(c.control, snapToStep(it, c))
            },
            onValueChangeFinished = { dragging = false },
        )
    }
}

/** Rounds a slider position to the camera's resolution (GET_RES) within its range. */
internal fun snapToStep(position: Float, c: ControlValue): Int {
    val step = c.step.coerceAtLeast(1)
    val snapped = c.min + ((position - c.min) / step).roundToInt() * step
    return snapped.coerceIn(c.min, c.max)
}
