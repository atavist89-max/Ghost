package com.ghost.app.capture

import android.app.Activity
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
        private const val CAPTURE_DPI = 1

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
     * @param activity Activity context
     * @param result ActivityResult from MediaProjection permission dialog
     * @param onCapture Callback with captured bitmap (null if failed)
     */
    fun startCapture(activity: Activity, result: ActivityResult, onCapture: (Bitmap?) -> Unit) {
        Log.d(TAG, "startCapture called with resultCode: ${result.resultCode}")
        
        if (result.resultCode != Activity.RESULT_OK) {
            Log.w(TAG, "MediaProjection permission denied, resultCode: ${result.resultCode}")
            onCapture(null)
            return
        }

        if (result.data == null) {
            Log.e(TAG, "MediaProjection result data is null")
            onCapture(null)
            return
        }

        try {
            // Initialize MediaProjection
            Log.d(TAG, "Creating MediaProjection")
            mediaProjection = mediaProjectionManager.getMediaProjection(result.resultCode, result.data!!)
            
            if (mediaProjection == null) {
                Log.e(TAG, "MediaProjection is null after creation")
                stopCapture()
                onCapture(null)
                return
            }
            
            // Register callback
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.w(TAG, "MediaProjection stopped by system")
                    stopCapture()
                }
            }, mainHandler)
            
            Log.d(TAG, "MediaProjection created successfully")

            // Create ImageReader for capturing frames
            Log.d(TAG, "Creating ImageReader: ${CAPTURE_WIDTH}x${CAPTURE_HEIGHT}")
            imageReader = ImageReader.newInstance(
                CAPTURE_WIDTH,
                CAPTURE_HEIGHT,
                android.graphics.PixelFormat.RGBA_8888,
                MAX_IMAGES
            )

            // Set up image available listener
            imageReader?.setOnImageAvailableListener({ reader ->
                Log.d(TAG, "Image available from ImageReader")
                try {
                    val image = reader.acquireLatestImage()
                    if (image != null) {
                        Log.d(TAG, "Image acquired, converting to bitmap")
                        val bitmap = BitmapConverter.imageToBitmap(image)
                        image.close()

                        // Stop capture immediately after getting one frame
                        stopCapture()

                        // Return bitmap on main thread
                        mainHandler.post {
                            Log.d(TAG, "Returning captured bitmap")
                            onCapture(bitmap)
                        }
                    } else {
                        Log.w(TAG, "acquireLatestImage returned null")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing captured image: ${e.message}", e)
                    stopCapture()
                    mainHandler.post {
                        onCapture(null)
                    }
                }
            }, mainHandler)

            // Create VirtualDisplay
            Log.d(TAG, "Creating VirtualDisplay")
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
            
            if (virtualDisplay == null) {
                Log.e(TAG, "VirtualDisplay is null after creation")
                stopCapture()
                onCapture(null)
                return
            }
            
            Log.d(TAG, "VirtualDisplay created successfully")
            Log.d(TAG, "Screen capture started, waiting for frame...")

            // Add timeout
            mainHandler.postDelayed({
                if (virtualDisplay != null) {
                    Log.e(TAG, "Screen capture timeout - no frame received within 3 seconds")
                    stopCapture()
                    mainHandler.post {
                        onCapture(null)
                    }
                }
            }, 3000)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start screen capture: ${e.message}", e)
            stopCapture()
            onCapture(null)
        }
    }

    /**
     * Stops the screen capture and releases all resources.
     */
    fun stopCapture() {
        Log.d(TAG, "Stopping screen capture")

        // Remove any pending callbacks
        mainHandler.removeCallbacksAndMessages(null)

        try {
            virtualDisplay?.release()
            virtualDisplay = null
            Log.d(TAG, "VirtualDisplay released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing virtual display: ${e.message}")
        }

        try {
            imageReader?.close()
            imageReader = null
            Log.d(TAG, "ImageReader closed")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing image reader: ${e.message}")
        }

        try {
            mediaProjection?.stop()
            mediaProjection = null
            Log.d(TAG, "MediaProjection stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping media projection: ${e.message}")
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
