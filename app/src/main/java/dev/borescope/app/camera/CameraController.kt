package dev.borescope.app.camera

import dev.borescope.uvc.CameraDevice
import dev.borescope.uvc.FrameListener
import dev.borescope.uvc.UvcFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

sealed interface CameraState {
    /** Not wanted (app in background); the camera is released. */
    data object Idle : CameraState
    data object NoDevice : CameraState
    data object Connecting : CameraState
    /** A camera is attached but we may not use it yet. The UI offers a button that calls [CameraController.retry]. */
    data object NeedsPermission : CameraState
    data class Streaming(
        val format: UvcFormat,
        val formats: List<UvcFormat>,
        val diagnostics: String,
    ) : CameraState
    data class Error(val message: String) : CameraState
}

/**
 * Owns the camera's lifecycle.
 *
 * Every camera operation runs on [dispatcher] (one dedicated thread in the app),
 * serialized through a command queue, so:
 *  - the public methods never block: they only enqueue;
 *  - operations can't race: a second [start] while connecting is a no-op, and a
 *    [stop] that arrives mid-connect closes the camera once the connect finishes;
 *  - blocking native teardown never runs on the main thread.
 *
 * Permission: [start] may show the system dialog once. After the user declines,
 * later starts don't prompt again (no dialog loop on every resume); [retry] does.
 * Attach events never prompt: when the app is launched or re-targeted by the
 * USB_DEVICE_ATTACHED intent, Android has already granted permission.
 *
 * A stream that stops delivering frames for [stallTimeoutMs] is closed and
 * reported as an error, so the UI never shows a frozen picture as live.
 */
class CameraController(
    private val source: CameraSource,
    dispatcher: CoroutineDispatcher,
    private val frames: FrameListener,
    private val stallTimeoutMs: Long = DEFAULT_STALL_TIMEOUT_MS,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private sealed interface Command {
        data object Start : Command
        data object Stop : Command
        data object Retry : Command
        data object Attached : Command
        data class Detached(val id: String) : Command
        data class Stalled(val session: Int) : Command
        data object Shutdown : Command
    }

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val commands = Channel<Command>(Channel.UNLIMITED)

    private val _state = MutableStateFlow<CameraState>(CameraState.Idle)
    val state: StateFlow<CameraState> = _state.asStateFlow()

    /** What the UI wants; written by callers, read by the actor. */
    @Volatile private var wanted = false

    /** A permission dialog we're waiting on; cancelled by [stop] and [shutdown]. */
    @Volatile private var permissionRequest: Job? = null

    /** Written from the native frame thread, read by the watchdog. */
    @Volatile private var lastFrameAt = 0L

    // Confined to the actor.
    private var device: CameraDevice? = null
    private var deviceId: String? = null
    private var session = 0
    private var permissionDeclined = false
    private var watchdog: Job? = null

    init {
        scope.launch { for (command in commands) handleSafely(command) }
        scope.launch { source.attachEvents.collect { commands.send(Command.Attached) } }
        scope.launch { source.detachEvents.collect { commands.send(Command.Detached(it)) } }
    }

    /** The UI became visible: connect to the first camera. */
    fun start() {
        wanted = true
        commands.trySend(Command.Start)
    }

    /** The UI went away: release the camera. */
    fun stop() {
        wanted = false
        permissionRequest?.cancel()
        commands.trySend(Command.Stop)
    }

    /** User asked to try again: re-prompts for permission if needed. */
    fun retry() {
        commands.trySend(Command.Retry)
    }

    /** A USB_DEVICE_ATTACHED intent reached the activity (permission is granted with it). */
    fun deviceAttached() {
        commands.trySend(Command.Attached)
    }

    /**
     * Releases the camera and stops the controller. The returned job completes
     * once nothing runs on the dispatcher any more, so its thread can be closed.
     */
    fun shutdown(): Job {
        wanted = false
        permissionRequest?.cancel()
        commands.trySend(Command.Shutdown)
        return scope.coroutineContext.job
    }

    private suspend fun handleSafely(command: Command) {
        try {
            handle(command)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            disconnect()
            _state.value = CameraState.Error(e.message ?: e.toString())
        }
    }

    private suspend fun handle(command: Command) {
        when (command) {
            Command.Start -> if (wanted) connect(allowPrompt = !permissionDeclined)
            // A start that arrived after this stop wins; don't close and reopen.
            Command.Stop -> if (!wanted) {
                disconnect()
                _state.value = CameraState.Idle
            }
            Command.Retry -> if (wanted && device == null) {
                permissionDeclined = false
                connect(allowPrompt = true)
            }
            Command.Attached -> if (wanted && device == null) connect(allowPrompt = false)
            is Command.Detached -> {
                permissionDeclined = false   // a new plug-in is a new decision
                if (command.id == deviceId) disconnect()
                if (device == null) {
                    if (wanted) connect(allowPrompt = false) else _state.value = CameraState.Idle
                }
            }
            // Defensive: disconnect() cancels the watchdog, so a stale report shouldn't arrive.
            is Command.Stalled -> if (command.session == session && device != null) {
                disconnect()
                _state.value = CameraState.Error("No video from the camera for ${stallTimeoutMs / 1000} s")
            }
            Command.Shutdown -> {
                disconnect()
                _state.value = CameraState.Idle
                commands.close()
                scope.cancel()
            }
        }
    }

    private suspend fun connect(allowPrompt: Boolean) {
        if (device != null) return
        val id = source.attachedDevices().firstOrNull()
        if (id == null) {
            _state.value = CameraState.NoDevice
            return
        }

        if (!source.hasPermission(id)) {
            if (!allowPrompt) {
                _state.value = CameraState.NeedsPermission
                return
            }
            _state.value = CameraState.Connecting
            if (!awaitPermission(id)) {
                if (wanted) permissionDeclined = true
                _state.value = if (wanted) CameraState.NeedsPermission else CameraState.Idle
                return
            }
            // The dialog can take a while; the world may have moved on.
            if (!wanted) {
                _state.value = CameraState.Idle
                return
            }
            if (id !in source.attachedDevices()) {
                _state.value = CameraState.NoDevice
                return
            }
        }

        _state.value = CameraState.Connecting
        val opened = source.open(id)
        try {
            val formats = opened.formats
            val format = UvcFormat.preferred(formats)
                ?: error("No supported video format. Camera offers: ${formats.joinToString().ifEmpty { "nothing" }}")
            val thisSession = ++session
            lastFrameAt = clock()
            opened.startStreaming(format) { data, width, height, type ->
                lastFrameAt = clock()
                frames.onFrame(data, width, height, type)
            }
            device = opened
            deviceId = id
            _state.value = CameraState.Streaming(format, formats, opened.diagnostics)
            startWatchdog(thisSession)
        } catch (e: Exception) {
            if (device !== opened) closeQuietly(opened)
            throw e
        }
    }

    /**
     * Asks for permission without letting a dialog nobody answers hold up the
     * command queue: [stop] and [shutdown] cancel the wait (counts as "no").
     */
    private suspend fun awaitPermission(id: String): Boolean {
        val request = scope.async { source.requestPermission(id) }
        permissionRequest = request
        if (!wanted) request.cancel()   // stop() raced with us before the job was visible
        return try {
            request.await()
        } catch (e: CancellationException) {
            if (!scope.isActive) throw e   // we're shutting down ourselves
            false
        } finally {
            permissionRequest = null
        }
    }

    private fun startWatchdog(thisSession: Int) {
        watchdog = scope.launch {
            while (true) {
                delay(stallTimeoutMs / 2)
                if (clock() - lastFrameAt >= stallTimeoutMs) {
                    commands.send(Command.Stalled(thisSession))
                    return@launch
                }
            }
        }
    }

    /** Closes the camera, if open. Blocks until native threads have stopped. */
    private fun disconnect() {
        watchdog?.cancel()
        watchdog = null
        val open = device ?: return
        device = null
        deviceId = null
        closeQuietly(open)
    }

    // After a cable pull, close() can report errors for a device that no longer
    // exists; there's nothing left to release, so they're not actionable.
    private fun closeQuietly(camera: CameraDevice) {
        try {
            camera.close()
        } catch (_: Exception) {
        }
    }

    companion object {
        const val DEFAULT_STALL_TIMEOUT_MS = 5_000L
    }
}
