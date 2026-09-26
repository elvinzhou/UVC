package dev.borescope.app.camera

import dev.borescope.uvc.FrameListener
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CameraControllerTest {

    private val source = FakeSource()
    private var framesDelivered = 0

    private fun TestScope.newController(stallTimeoutMs: Long = 5_000) = CameraController(
        source = source,
        dispatcher = StandardTestDispatcher(testScheduler),
        frames = FrameListener { _, _, _, _, _ -> framesDelivered++ },
        stallTimeoutMs = stallTimeoutMs,
        clock = { testScheduler.currentTime },
    ).also { runCurrent() }   // let it subscribe to attach/detach events

    /** Runs a test and always shuts the controller down so no coroutine outlives it. */
    private fun controllerTest(body: suspend TestScope.(CameraController) -> Unit) = runTest {
        val controller = newController()
        try {
            body(controller)
        } finally {
            controller.shutdown()
            runCurrent()
        }
    }

    private val CameraController.current get() = state.value

    // --- Connecting ---------------------------------------------------------

    @Test
    fun start_withNoCamera_reportsNoDevice() = controllerTest { c ->
        c.start(); runCurrent()
        assertEquals(CameraState.NoDevice, c.current)
    }

    @Test
    fun start_streamsLargestMjpegMode() = controllerTest { c ->
        source.plugIn("cam", permission = true)
        c.start(); runCurrent()

        val state = c.current as CameraState.Streaming
        assertEquals(MJPEG_720, state.format)
        assertEquals(listOf(MJPEG_480, MJPEG_720), state.formats)
        assertEquals("DEVICE CONFIGURATION (fake)", state.diagnostics)
        assertEquals(MJPEG_720, source.opened.single().streamingFormat)
    }

    @Test
    fun start_neverTouchesTheCameraOnTheCallingThread() = controllerTest { c ->
        source.plugIn("cam", permission = true)
        c.start()
        // Nothing has run on the camera dispatcher yet, so nothing was opened.
        assertTrue(source.opened.isEmpty())
        runCurrent()
        assertEquals(1, source.opened.size)
    }

    @Test
    fun framesAreForwarded() = controllerTest { c ->
        source.plugIn("cam", permission = true)
        c.start(); runCurrent()
        repeat(3) { source.opened.single().emitFrame() }
        assertEquals(3, framesDelivered)
    }

    @Test
    fun repeatedStart_opensOnce() = controllerTest { c ->
        source.plugIn("cam", permission = true)
        c.start(); c.start(); runCurrent()
        c.start(); runCurrent()
        assertEquals(1, source.opened.size)
    }

    // --- Stopping and resuming ---------------------------------------------

    @Test
    fun stop_releasesCamera() = controllerTest { c ->
        source.plugIn("cam", permission = true)
        c.start(); runCurrent()
        c.stop(); runCurrent()
        assertTrue(source.opened.single().closed)
        assertEquals(CameraState.Idle, c.current)
    }

    @Test
    fun resume_reconnectsWithoutPrompting() = controllerTest { c ->
        source.plugIn("cam", permission = false)
        c.start(); runCurrent()
        assertEquals(1, source.permissionRequests)

        c.stop(); runCurrent()
        c.start(); runCurrent()

        assertEquals(1, source.permissionRequests)
        assertEquals(2, source.opened.size)
        assertTrue(source.opened[0].closed)
        assertTrue(c.current is CameraState.Streaming)
    }

    @Test
    fun stopThenStartBeforeProcessing_keepsCameraOpen() = controllerTest { c ->
        source.plugIn("cam", permission = true)
        c.start(); runCurrent()
        c.stop(); c.start(); runCurrent()   // quick background/foreground bounce
        assertEquals(1, source.opened.size)
        assertFalse(source.opened.single().closed)
        assertTrue(c.current is CameraState.Streaming)
    }

    @Test
    fun startThenStopBeforeProcessing_endsReleased() = controllerTest { c ->
        source.plugIn("cam", permission = true)
        c.start(); c.stop(); runCurrent()
        assertTrue(source.opened.all { it.closed })
        assertEquals(CameraState.Idle, c.current)
    }

    // --- Permission ---------------------------------------------------------

    @Test
    fun declinedPermission_isNotAskedAgainOnResume() = controllerTest { c ->
        source.permissionAnswer = false
        source.plugIn("cam", permission = false)
        c.start(); runCurrent()
        assertEquals(CameraState.NeedsPermission, c.current)

        c.stop(); runCurrent()
        c.start(); runCurrent()
        assertEquals(1, source.permissionRequests)
        assertEquals(CameraState.NeedsPermission, c.current)
    }

    @Test
    fun retry_asksAgainAndStreamsWhenGranted() = controllerTest { c ->
        source.permissionAnswer = false
        source.plugIn("cam", permission = false)
        c.start(); runCurrent()

        source.permissionAnswer = true
        c.retry(); runCurrent()
        assertEquals(2, source.permissionRequests)
        assertTrue(c.current is CameraState.Streaming)
    }

    @Test
    fun stopWhileDialogShown_doesNotOpenAfterwards() = controllerTest { c ->
        source.permissionAnswer = null   // dialog stays up
        source.plugIn("cam", permission = false)
        c.start(); runCurrent()
        assertEquals(CameraState.Connecting, c.current)

        c.stop(); runCurrent()
        source.pendingPermission.complete(true)
        runCurrent()

        assertTrue(source.opened.isEmpty())
        assertEquals(CameraState.Idle, c.current)
    }

    @Test
    fun stopWhileDialogShown_doesNotCountAsDeclined() = controllerTest { c ->
        source.permissionAnswer = null
        source.plugIn("cam", permission = false)
        c.start(); runCurrent()
        c.stop(); runCurrent()

        source.permissionAnswer = true
        c.start(); runCurrent()
        assertEquals(2, source.permissionRequests)
        assertTrue(c.current is CameraState.Streaming)
    }

    @Test
    fun shutdownWhileDialogShown_completes() = runTest {
        val c = newController()
        source.permissionAnswer = null
        source.plugIn("cam", permission = false)
        c.start(); runCurrent()

        val job = c.shutdown(); runCurrent()
        assertTrue(job.isCompleted)
        assertTrue(source.opened.isEmpty())
    }

    @Test
    fun unpluggedWhileDialogShown_reportsNoDevice() = controllerTest { c ->
        source.permissionAnswer = null
        source.plugIn("cam", permission = false)
        c.start(); runCurrent()

        source.unplug("cam")
        source.pendingPermission.complete(true)
        runCurrent()
        assertTrue(source.opened.isEmpty())
        assertEquals(CameraState.NoDevice, c.current)
    }

    // --- Plug and unplug ----------------------------------------------------

    @Test
    fun attachWithPermission_connects() = controllerTest { c ->
        c.start(); runCurrent()
        assertEquals(CameraState.NoDevice, c.current)

        source.plugIn("cam", permission = true)
        source.attachEvents.emit("cam"); runCurrent()
        assertTrue(c.current is CameraState.Streaming)
    }

    @Test
    fun attachWithoutPermission_neverPrompts() = controllerTest { c ->
        c.start(); runCurrent()
        source.plugIn("cam", permission = false)
        source.attachEvents.emit("cam"); runCurrent()

        assertEquals(0, source.permissionRequests)
        assertEquals(CameraState.NeedsPermission, c.current)

        // The system's "open with Borescope?" dialog grants it, then the intent arrives.
        source.permitted += "cam"
        c.deviceAttached(); runCurrent()
        assertTrue(c.current is CameraState.Streaming)
    }

    @Test
    fun attachWhileBackgrounded_isIgnored() = controllerTest { c ->
        source.plugIn("cam", permission = true)
        source.attachEvents.emit("cam"); runCurrent()
        assertTrue(source.opened.isEmpty())
        assertEquals(CameraState.Idle, c.current)
    }

    @Test
    fun unplugWhileStreaming_closesAndReportsNoDevice() = controllerTest { c ->
        source.plugIn("cam", permission = true)
        c.start(); runCurrent()

        source.unplug("cam")
        source.detachEvents.emit("cam"); runCurrent()

        assertTrue(source.opened.single().closed)
        assertEquals(CameraState.NoDevice, c.current)
    }

    @Test
    fun replug_reconnects() = controllerTest { c ->
        source.plugIn("cam", permission = true)
        c.start(); runCurrent()
        source.unplug("cam")
        source.detachEvents.emit("cam"); runCurrent()

        source.plugIn("cam2", permission = true)
        source.attachEvents.emit("cam2"); runCurrent()
        assertEquals(2, source.opened.size)
        assertTrue(c.current is CameraState.Streaming)
    }

    @Test
    fun unplugOfAnotherDevice_keepsStreaming() = controllerTest { c ->
        source.plugIn("cam", permission = true)
        c.start(); runCurrent()
        source.detachEvents.emit("other"); runCurrent()
        assertFalse(source.opened.single().closed)
        assertTrue(c.current is CameraState.Streaming)
    }

    @Test
    fun unplugThenDeclinedCamera_isAskedAgainAfterReplug() = controllerTest { c ->
        source.permissionAnswer = false
        source.plugIn("cam", permission = false)
        c.start(); runCurrent()
        source.unplug("cam")
        source.detachEvents.emit("cam"); runCurrent()

        source.permissionAnswer = true
        source.plugIn("cam", permission = false)
        c.stop(); runCurrent()
        c.start(); runCurrent()
        assertEquals(2, source.permissionRequests)
        assertTrue(c.current is CameraState.Streaming)
    }

    // --- Failures -----------------------------------------------------------

    @Test
    fun openFailure_reportsErrorAndRetryRecovers() = controllerTest { c ->
        source.plugIn("cam", permission = true)
        source.openError = IllegalStateException("Native open failed")
        c.start(); runCurrent()
        assertEquals(CameraState.Error("Native open failed"), c.current)

        source.openError = null
        c.retry(); runCurrent()
        assertTrue(c.current is CameraState.Streaming)
    }

    @Test
    fun startStreamingFailure_closesTheDevice() = controllerTest { c ->
        source.plugIn("cam", permission = true)
        source.nextDevice = { FakeDevice(startError = IllegalStateException("uvc error -51")) }
        c.start(); runCurrent()
        assertEquals(CameraState.Error("uvc error -51"), c.current)
        assertTrue(source.opened.single().closed)
    }

    @Test
    fun noDisplayableFormat_closesAndExplains() = controllerTest { c ->
        source.plugIn("cam", permission = true)
        source.nextDevice = { FakeDevice(formats = listOf(H264_1080)) }
        c.start(); runCurrent()
        val error = c.current as CameraState.Error
        assertTrue(error.message, error.message.contains("H264 1920x1080@30"))
        assertTrue(source.opened.single().closed)
    }

    // --- Stall watchdog -----------------------------------------------------

    @Test
    fun steadyFrames_keepStreaming() = controllerTest { c ->
        source.plugIn("cam", permission = true)
        c.start(); runCurrent()
        repeat(20) {
            advanceTimeBy(1_000); runCurrent()
            source.opened.single().emitFrame()
        }
        assertTrue(c.current is CameraState.Streaming)
    }

    @Test
    fun noFrames_closesAndReportsStall() = controllerTest { c ->
        source.plugIn("cam", permission = true)
        c.start(); runCurrent()
        source.opened.single().emitFrame()

        advanceTimeBy(7_600); runCurrent()
        assertTrue(source.opened.single().closed)
        assertEquals(CameraState.Error("No video from the camera for 5 s"), c.current)
    }

    @Test
    fun stallFromOldSession_doesNotKillNewOne() = controllerTest { c ->
        source.plugIn("cam", permission = true)
        c.start(); runCurrent()
        c.stop(); runCurrent()
        c.start(); runCurrent()
        repeat(10) {
            advanceTimeBy(1_000); runCurrent()
            source.opened.last().emitFrame()
        }
        assertTrue(c.current is CameraState.Streaming)
        assertFalse(source.opened.last().closed)
    }

    // --- Format selection ---------------------------------------------------

    private fun TestScope.streamingWith(c: CameraController, vararg formats: dev.borescope.uvc.UvcFormat): FakeDevice {
        source.plugIn("cam", permission = true)
        source.nextDevice = { FakeDevice(formats = formats.toList()) }
        c.start(); runCurrent()
        return source.opened.last()
    }

    @Test
    fun selectFormat_switchesTheStream() = controllerTest { c ->
        val cam = streamingWith(c, MJPEG_480, MJPEG_720, YUYV_480)
        c.selectFormat(YUYV_480); runCurrent()
        assertEquals(YUYV_480, cam.streamingFormat)
        assertEquals(YUYV_480, (c.current as CameraState.Streaming).format)
        assertEquals(1, source.opened.size)   // same device, no reopen
    }

    @Test
    fun selectFormat_ignoresFormatsTheCameraDoesNotOffer() = controllerTest { c ->
        val cam = streamingWith(c, MJPEG_480, MJPEG_720)
        c.selectFormat(YUYV_480); runCurrent()
        assertEquals(MJPEG_720, cam.streamingFormat)
    }

    @Test
    fun selectFormat_refusesUndisplayableWithNotice() = runTest {
        val c = newController()
        val notices = mutableListOf<String>()
        val collector = backgroundScope.launch(StandardTestDispatcher(testScheduler)) { c.notices.collect { notices += it } }
        val cam = streamingWith(c, MJPEG_720, H264_1080)
        c.selectFormat(H264_1080); runCurrent()
        assertEquals(MJPEG_720, cam.streamingFormat)
        assertEquals(listOf("H264 1920x1080@30 can't be displayed"), notices)
        collector.cancel(); c.shutdown(); runCurrent()
    }

    @Test
    fun selectFormat_rejectedByCamera_revertsWithNotice() = runTest {
        val c = newController()
        val notices = mutableListOf<String>()
        backgroundScope.launch(StandardTestDispatcher(testScheduler)) { c.notices.collect { notices += it } }
        val cam = streamingWith(c, MJPEG_480, MJPEG_720)
        cam.rejects = setOf(MJPEG_480)
        c.selectFormat(MJPEG_480); runCurrent()

        assertEquals(MJPEG_720, cam.streamingFormat)
        assertEquals(MJPEG_720, (c.current as CameraState.Streaming).format)
        assertEquals(1, notices.size)
        c.shutdown(); runCurrent()
    }

    @Test
    fun selectFormat_bothRejected_closesWithError() = controllerTest { c ->
        val cam = streamingWith(c, MJPEG_480, MJPEG_720)
        cam.rejects = setOf(MJPEG_480, MJPEG_720)
        c.selectFormat(MJPEG_480); runCurrent()
        assertTrue(cam.closed)
        assertTrue(c.current is CameraState.Error)
    }

    @Test
    fun selectFormat_isRememberedAcrossReconnects() = controllerTest { c ->
        streamingWith(c, MJPEG_480, MJPEG_720)
        c.selectFormat(MJPEG_480); runCurrent()
        c.stop(); runCurrent()
        c.start(); runCurrent()
        assertEquals(MJPEG_480, source.opened.last().streamingFormat)
    }

    @Test
    fun selectFormat_resetsStallWatchdog() = controllerTest { c ->
        val cam = streamingWith(c, MJPEG_480, MJPEG_720)
        advanceTimeBy(4_000); runCurrent()
        c.selectFormat(MJPEG_480); runCurrent()
        advanceTimeBy(3_000); runCurrent()   // 7 s since start, 3 s since the switch
        assertTrue(c.current is CameraState.Streaming)
        assertFalse(cam.closed)
    }

    // --- Image controls -----------------------------------------------------

    private fun CameraController.controlValue(c: dev.borescope.uvc.UvcControl) =
        (state.value as CameraState.Streaming).controls.single { it.control == c }

    @Test
    fun controls_areReadOnConnect() = controllerTest { c ->
        source.plugIn("cam", permission = true)
        c.start(); runCurrent()
        assertEquals(3, (c.current as CameraState.Streaming).controls.size)
    }

    @Test
    fun controls_failingReadStillStreams() = controllerTest { c ->
        source.plugIn("cam", permission = true)
        source.nextDevice = { FakeDevice().apply { readControlsError = IllegalStateException("stall") } }
        c.start(); runCurrent()
        val state = c.current as CameraState.Streaming
        assertEquals(emptyList<dev.borescope.uvc.ControlValue>(), state.controls)
    }

    @Test
    fun setControl_appliesAndRefreshes() = controllerTest { c ->
        source.plugIn("cam", permission = true)
        c.start(); runCurrent()
        c.setControl(dev.borescope.uvc.UvcControl.BRIGHTNESS, 20); runCurrent()
        assertEquals(20, c.controlValue(dev.borescope.uvc.UvcControl.BRIGHTNESS).current)
    }

    @Test
    fun setControl_coalescesRapidChanges() = controllerTest { c ->
        source.plugIn("cam", permission = true)
        c.start(); runCurrent()
        for (v in 1..10) c.setControl(dev.borescope.uvc.UvcControl.BRIGHTNESS, v)
        runCurrent()
        assertEquals(listOf(dev.borescope.uvc.UvcControl.BRIGHTNESS to 10), source.opened.single().setCalls)
        assertEquals(10, c.controlValue(dev.borescope.uvc.UvcControl.BRIGHTNESS).current)
    }

    @Test
    fun setControl_refusedIsANoticeNotAnError() = runTest {
        val c = newController()
        val notices = mutableListOf<String>()
        backgroundScope.launch(StandardTestDispatcher(testScheduler)) { c.notices.collect { notices += it } }
        source.plugIn("cam", permission = true)
        c.start(); runCurrent()
        source.opened.single().refusedControls = setOf(dev.borescope.uvc.UvcControl.BRIGHTNESS)

        c.setControl(dev.borescope.uvc.UvcControl.BRIGHTNESS, 30); runCurrent()
        assertTrue(c.current is CameraState.Streaming)
        assertEquals(0, c.controlValue(dev.borescope.uvc.UvcControl.BRIGHTNESS).current)
        assertEquals(listOf("Camera refused Brightness = 30"), notices)
        c.shutdown(); runCurrent()
    }

    @Test
    fun setControl_whileNotStreamingIsDropped() = controllerTest { c ->
        c.setControl(dev.borescope.uvc.UvcControl.BRIGHTNESS, 30); runCurrent()
        source.plugIn("cam", permission = true)
        c.start(); runCurrent()
        assertTrue(source.opened.single().setCalls.isEmpty())
    }

    @Test
    fun resetControls_restoresDefaultsAutoFirst() = controllerTest { c ->
        source.plugIn("cam", permission = true)
        c.start(); runCurrent()
        val cam = source.opened.single()
        c.setControl(dev.borescope.uvc.UvcControl.WHITE_BALANCE, 3000)
        c.setControl(dev.borescope.uvc.UvcControl.WHITE_BALANCE_AUTO, 0)
        c.setControl(dev.borescope.uvc.UvcControl.BRIGHTNESS, 10)
        runCurrent()
        cam.setCalls.clear()

        c.resetControls(); runCurrent()
        assertEquals(dev.borescope.uvc.UvcControl.WHITE_BALANCE_AUTO, cam.setCalls.first().first)
        assertEquals(0, c.controlValue(dev.borescope.uvc.UvcControl.BRIGHTNESS).current)
        assertEquals(1, c.controlValue(dev.borescope.uvc.UvcControl.WHITE_BALANCE_AUTO).current)
        assertEquals(4600, c.controlValue(dev.borescope.uvc.UvcControl.WHITE_BALANCE).current)
    }

    // --- Shutdown -----------------------------------------------------------

    @Test
    fun shutdown_closesCameraAndCompletes() = runTest {
        val c = newController()
        source.plugIn("cam", permission = true)
        c.start(); runCurrent()

        val job = c.shutdown(); runCurrent()
        assertTrue(job.isCompleted)
        assertEquals(1, source.opened.single().closeCount)
        assertEquals(CameraState.Idle, c.current)
    }

    @Test
    fun commandsAfterShutdown_areIgnored() = runTest {
        val c = newController()
        source.plugIn("cam", permission = true)
        c.shutdown(); runCurrent()
        c.start(); c.retry(); c.deviceAttached(); runCurrent()
        assertTrue(source.opened.isEmpty())
    }
}
