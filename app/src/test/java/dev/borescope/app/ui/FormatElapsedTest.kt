package dev.borescope.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatElapsedTest {
    @Test
    fun formats() {
        assertEquals("0:00", formatElapsed(0))
        assertEquals("0:00", formatElapsed(-500))
        assertEquals("0:09", formatElapsed(9_999))
        assertEquals("1:05", formatElapsed(65_000))
        assertEquals("59:59", formatElapsed(3_599_000))
        assertEquals("1:00:00", formatElapsed(3_600_000))
    }
}
