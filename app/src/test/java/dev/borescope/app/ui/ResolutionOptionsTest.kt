package dev.borescope.app.ui

import dev.borescope.app.camera.CameraState
import dev.borescope.uvc.UvcFormat
import dev.borescope.uvc.UvcFormat.Type
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolutionOptionsTest {
    private fun f(type: Type, w: Int, h: Int, fps: Int) = UvcFormat(type, w, h, fps)

    @Test fun `sorted largest first then fastest and hides undisplayable modes`() {
        val out = resolutionOptions(
            listOf(
                f(Type.MJPEG, 640, 480, 30),
                f(Type.H264, 1920, 1080, 30),
                f(Type.MJPEG, 1280, 720, 15),
                f(Type.MJPEG, 1280, 720, 30),
                f(Type.OTHER, 800, 600, 30),
            ),
        )
        assertEquals(
            listOf("1280×720 · 30 fps", "1280×720 · 15 fps", "640×480 · 30 fps"),
            out.map { it.label },
        )
    }

    @Test fun `mjpeg ranks above raw yuyv at the same size and labels raw`() {
        val out = resolutionOptions(listOf(f(Type.YUYV, 640, 480, 30), f(Type.MJPEG, 640, 480, 30)))
        assertEquals(listOf("640×480 · 30 fps", "640×480 · 30 fps · raw"), out.map { it.label })
        assertEquals(Type.MJPEG, out.first().format.type)
    }

    @Test fun `duplicates collapse`() {
        val m = f(Type.MJPEG, 640, 480, 30)
        assertEquals(1, resolutionOptions(listOf(m, m)).size)
    }

    @Test fun `no camera hints`() {
        assertTrue(noCameraHint(CameraState.NoDevice).startsWith("No camera connected"))
        assertTrue(noCameraHint(CameraState.Idle).startsWith("No camera connected"))
        assertTrue(noCameraHint(CameraState.Connecting).startsWith("Connecting"))
    }
}
