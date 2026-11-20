package dev.borescope.app.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewportStateTest {
    private fun state() = ViewportState().also { it.box = IntSize(1000, 2000) }

    @Test fun `zoom is clamped between 1 and the maximum`() {
        val s = state()
        s.zoomBy(100f)
        assertEquals(MAX_ZOOM, s.zoom, 0f)
        s.zoomBy(0.0001f)
        assertEquals(1f, s.zoom, 0f)
        assertFalse(s.isZoomed)
    }

    @Test fun `zooming back out to 1 recentres the picture`() {
        val s = state()
        s.zoomBy(2f)
        s.panBy(Offset(300f, 300f))
        s.zoomBy(0.5f)
        assertEquals(0f, s.panX, 0f)
        assertEquals(0f, s.panY, 0f)
    }

    @Test fun `panning cannot leave the zoomed picture`() {
        val s = state()
        s.zoomBy(2f)
        s.panBy(Offset(10_000f, -10_000f))
        assertEquals(500f, s.panX, 0f) // 1000 * (2-1) / 2
        assertEquals(-1000f, s.panY, 0f)
    }

    @Test fun `reset returns to the full frame`() {
        val s = state()
        s.zoomBy(3f)
        assertTrue(s.isZoomed)
        s.reset()
        assertFalse(s.isZoomed)
    }

    @Test fun `zoom label drops a trailing zero`() {
        assertEquals("1×", zoomLabel(1f))
        assertEquals("2.5×", zoomLabel(2.5f))
        assertEquals("8×", zoomLabel(8f))
    }
}
