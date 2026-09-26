package dev.borescope.app.ui

import dev.borescope.uvc.ControlValue
import dev.borescope.uvc.UvcControl
import org.junit.Assert.assertEquals
import org.junit.Test

class SnapToStepTest {
    private val wb = ControlValue(UvcControl.WHITE_BALANCE, 2800, 6500, 10, 4600, 4600)

    @Test
    fun snapsToResolutionFromMin() {
        assertEquals(2800, snapToStep(2804f, wb))
        assertEquals(2810, snapToStep(2806f, wb))
        assertEquals(6500, snapToStep(6499f, wb))
    }

    @Test
    fun staysInRange() {
        assertEquals(2800, snapToStep(0f, wb))
        assertEquals(6500, snapToStep(9999f, wb))
    }

    @Test
    fun zeroStepTreatedAsOne() {
        val b = ControlValue(UvcControl.BRIGHTNESS, -64, 64, 0, 0, 0)
        assertEquals(-3, snapToStep(-3.4f, b))
    }
}
