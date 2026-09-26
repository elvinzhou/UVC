package dev.borescope.app.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

class ClampPanTest {
    private val box = IntSize(1000, 500)

    @Test
    fun noPanAtNoZoom() {
        assertEquals(Offset.Zero, clampPan(Offset(300f, -200f), 1f, box))
    }

    @Test
    fun panLimitedToHalfTheExtraSize() {
        // 2x: picture is 2000x1000, so it can move 500 / 250 px each way.
        assertEquals(Offset(500f, -250f), clampPan(Offset(900f, -900f), 2f, box))
        assertEquals(Offset(-120f, 80f), clampPan(Offset(-120f, 80f), 2f, box))
    }
}
