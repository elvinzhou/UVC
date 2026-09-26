package dev.borescope.app.capture

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records decoded camera frames to an H.264 MP4 in Movies/Borescope.
 *
 * Frames are drawn onto the encoder's input Surface with a hardware Canvas;
 * the Surface stamps each buffer with its queue time, so timestamps follow
 * frame arrival. A drain thread moves encoded samples into MediaMuxer.
 *
 * [drawFrame] may be called from any one thread (the native frame thread);
 * [stop] from any other. They're serialized internally.
 */
class VideoRecorder private constructor(
    private val context: Context,
    private val uri: Uri,
    private val pfd: ParcelFileDescriptor,
    private val codec: MediaCodec,
    private val muxer: MediaMuxer,
    private val surface: Surface,
    private val spec: VideoSpec,
    private val mirrored: Boolean,
) {
    private val lock = Any()
    private var stopped = false
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = Rect(0, 0, spec.width, spec.height)

    @Volatile private var samplesWritten = 0
    private val drainThread = Thread(::drain, "video-encoder").apply { start() }

    /** Draws one frame, scaled to the video size. Ignored after [stop]. */
    fun drawFrame(frame: Bitmap) {
        synchronized(lock) {
            if (stopped) return
            try {
                val canvas = surface.lockHardwareCanvas()
                try {
                    if (mirrored) canvas.scale(-1f, 1f, spec.width / 2f, 0f)
                    canvas.drawBitmap(frame, null, dst, paint)
                } finally {
                    surface.unlockCanvasAndPost(canvas)
                }
            } catch (e: RuntimeException) {
                Log.w(TAG, "Dropping frame", e)
            }
        }
    }

    /**
     * Finishes the file and publishes it. Blocks while the encoder drains.
     * Returns the video's Uri, or null if nothing was recorded.
     */
    fun stop(): Uri? {
        synchronized(lock) {
            if (stopped) return null
            stopped = true
            runCatching { codec.signalEndOfInputStream() }
        }
        drainThread.join(DRAIN_TIMEOUT_MS)
        if (drainThread.isAlive) {
            Log.w(TAG, "Encoder didn't reach end of stream; abandoning")
            drainThread.interrupt()
        }
        val ok = samplesWritten > 0 && runCatching { muxer.stop() }.isSuccess
        runCatching { muxer.release() }
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { surface.release() }
        runCatching { pfd.close() }

        val resolver = context.contentResolver
        return if (ok) {
            resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            uri
        } else {
            resolver.delete(uri, null, null)
            null
        }
    }

    private fun drain() {
        val info = MediaCodec.BufferInfo()
        var track = -1
        while (!Thread.currentThread().isInterrupted) {
            val index = try {
                codec.dequeueOutputBuffer(info, 10_000)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Encoder failed", e)
                return
            }
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                }
                index >= 0 -> {
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!isConfig && info.size > 0 && track >= 0) {
                        val data = codec.getOutputBuffer(index)!!
                        data.position(info.offset)
                        data.limit(info.offset + info.size)
                        muxer.writeSampleData(track, data, info)
                        samplesWritten++
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    companion object {
        private const val TAG = "VideoRecorder"
        private const val DRAIN_TIMEOUT_MS = 5_000L

        /**
         * Starts a recording for frames of [width]x[height] at [fps]. Blocking
         * (codec setup), so call it off the main thread. [rotationDegrees] is
         * stored as the MP4 orientation hint; players rotate on playback.
         */
        fun start(
            context: Context,
            width: Int,
            height: Int,
            fps: Int,
            rotationDegrees: Int,
            mirrored: Boolean,
        ): VideoRecorder {
            val spec = VideoSpec.forStream(width, height, fps)
            val resolver = context.contentResolver
            val name = "BORESCOPE_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "$name.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Borescope")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("Couldn't create a video file")

            var pfd: ParcelFileDescriptor? = null
            var codec: MediaCodec? = null
            var muxer: MediaMuxer? = null
            try {
                pfd = resolver.openFileDescriptor(uri, "rw") ?: error("Couldn't open the video file")
                muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                muxer.setOrientationHint(((rotationDegrees % 360) + 360) % 360)

                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, spec.width, spec.height).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, spec.bitRate)
                    setInteger(MediaFormat.KEY_FRAME_RATE, spec.fps)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                }
                codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                val surface = codec.createInputSurface()
                codec.start()
                return VideoRecorder(context, uri, pfd, codec, muxer, surface, spec, mirrored)
            } catch (e: Exception) {
                runCatching { codec?.release() }
                runCatching { muxer?.release() }
                runCatching { pfd?.close() }
                resolver.delete(uri, null, null)
                throw e
            }
        }
    }
}
