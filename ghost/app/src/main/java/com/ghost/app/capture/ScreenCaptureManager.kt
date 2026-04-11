package com.ghost.app.capture

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.activity.result.ActivityResult

/**
 * Manages screen capture using MediaProjection API.
 * Captures exactly one frame and immediately releases resources.
 */
class ScreenCaptureManager(context: Context) {

    companion object {
        private const val TAG = "GhostCapture"

        // Capture dimensions - 1280x720 for performance
        private const val CAPTURE_WIDTH = 1280
        private const val CAPTURE_HEIGHT = 720
        private const val CAPTURE_DPI = 160

        // ImageReader configuration
        private const val MAX_IMAGES = 2
    }

    private val mediaProjectionManager =
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Creates the screen capture intent for permission request.
     * @return Intent to launch for MediaProjection permission
     */
    fun createCaptureIntent(): Intent {
        return mediaProjectionManager.createScreenCaptureIntent()
    }

    /**
     * Starts the screen capture process.
     * Captures exactly one frame and returns it via callback.
     *
     * @param result ActivityResult from MediaProjection permission dialog
     * @param onCapture Callback with captured bitmap (null if failed)
     */
    fun startCapture(result: ActivityResult, onCapture: (Bitmap?) -> Unit) {
        if (result.resultCode != android.app.Activity.RESULT_OK) {
            Log.w(TAG, "MediaProjection permission denied")
            onCapture(null)
            return
        }

        try {
            // Initialize MediaProjection
            mediaProjection = mediaProjectionManager.getMediaProjection(result.resultCode, result.data!!)

            // Create ImageReader for capturing frames
            imageReader = ImageReader.newInstance(
                CAPTURE_WIDTH,
                CAPTURE_HEIGHT,
                android.graphics.PixelFormat.RGBA_8888,
                MAX_IMAGES
            )

            // Set up image available listener
            imageReader?.setOnImageAvailableListener({ reader ->
                try {
                    val image = reader.acquireLatestImage()
                    if (image != null) {
                        val bitmap = BitmapConverter.imageToBitmap(image)
                        image.close()

                        // Stop capture immediately after getting one frame
                        stopCapture()

                        // Return bitmap on main thread
                        mainHandler.post {
                            onCapture(bitmap)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing captured image", e)
                    stopCapture()
                    mainHandler.post {
                        onCapture(null)
                    }
                }
            }, mainHandler)

            // Create VirtualDisplay
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "GhostScreenCapture",
                CAPTURE_WIDTH,
                CAPTURE_HEIGHT,
                CAPTURE_DPI,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                mainHandler
            )

            Log.d(TAG, "Screen capture started")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start screen capture", e)
            stopCapture()
            onCapture(null)
        }
    }

    /**
     * Stops the screen capture and releases all resources.
     * This should be called as soon as the frame is captured.
     */
    fun stopCapture() {
        Log.d(TAG, "Stopping screen capture")

        try {
            virtualDisplay?.release()
            virtualDisplay = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing virtual display", e)
        }

        try {
            imageReader?.close()
            imageReader = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing image reader", e)
        }

        try {
            mediaProjection?.stop()
            mediaProjection = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping media projection", e)
        }

        Log.d(TAG, "Screen capture stopped and resources released")
    }

    /**
     * Check if currently capturing.
     */
    fun isCapturing(): Boolean {
        return mediaProjection != null && virtualDisplay != null
    }
}
