package dev.borescope.app.capture

/**
 * TODO (milestone 3): MJPEG frames -> H.264 MP4.
 *
 * Plan:
 *  - MediaCodec encoder ("video/avc") with COLOR_FormatSurface input
 *  - Draw each decoded frame Bitmap onto encoder.createInputSurface() via
 *    Surface.lockHardwareCanvas() (simple) or EGL/GL (faster)
 *  - Presentation timestamps from frame arrival time (System.nanoTime)
 *  - MediaMuxer -> MediaStore.Video (Movies/Borescope)
 *  - Apply the UI rotation via MediaMuxer.setOrientationHint()
 */
class VideoRecorder
