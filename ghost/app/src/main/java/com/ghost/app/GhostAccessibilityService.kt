package com.ghost.app

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.hardware.HardwareBuffer
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.Executor

/**
 * AccessibilityService that provides silent screenshot capability.
 * 
 * Unlike MediaProjection, this requires no permission dialog and shows no status bar indicator.
 * The service is enabled once in Settings and persists across reboots.
 * 
 * Reference: Pokeclaw architecture (github.com/agents-io/PokeClaw)
 */
class GhostAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "GhostAccessibility"
        
        // Singleton instance for Activity to access
        @Volatile
        private var _instance: GhostAccessibilityService? = null
        
        /**
         * Get the current service instance if connected.
         */
        val instance: GhostAccessibilityService?
            get() = _instance
        
        /**
         * Check if the service is connected and ready for screenshots.
         */
        fun isConnected(): Boolean = _instance != null
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor: Executor = Executor { command ->
        mainHandler.post(command)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "AccessibilityService connected")
        _instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not needed for screenshot functionality
        // Required override - leave empty
    }

    override fun onInterrupt() {
        // Required override - leave empty
        Log.w(TAG, "AccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "AccessibilityService destroyed")
        if (_instance === this) {
            _instance = null
        }
    }

    /**
     * Take a screenshot using AccessibilityService API (API 30+).
     * This is silent - no dialog, no status bar indicator.
     * 
     * @param callback Function to receive the captured bitmap (null if failed)
     */
    fun takeScreenshotForGhost(callback: (Bitmap?) -> Unit) {
        Log.d(TAG, "Requesting screenshot via AccessibilityService")
        
        try {
            // Use API 30+ takeScreenshot with proper callback interface
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                executor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshotResult: ScreenshotResult) {
                        val hardwareBuffer = screenshotResult.hardwareBuffer
                        val colorSpace = screenshotResult.colorSpace
                        
                        if (hardwareBuffer == null) {
                            Log.e(TAG, "HardwareBuffer is null")
                            callback(null)
                            return
                        }
                        
                        try {
                            // Convert HardwareBuffer to Bitmap using wrapHardwareBuffer
                            val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                            if (bitmap != null) {
                                // Create a mutable copy since wrapped bitmap may be immutable
                                val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                                bitmap.recycle()
                                Log.i(TAG, "Screenshot captured: ${mutableBitmap.width}x${mutableBitmap.height}")
                                callback(mutableBitmap)
                            } else {
                                Log.e(TAG, "Bitmap.wrapHardwareBuffer returned null")
                                callback(null)
                            }
                        } finally {
                            // CRITICAL: Always close HardwareBuffer to prevent memory leaks
                            hardwareBuffer.close()
                            Log.d(TAG, "HardwareBuffer closed")
                        }
                    }
                    
                    override fun onFailure(errorCode: Int) {
                        Log.e(TAG, "Screenshot failed with error code: $errorCode")
                        callback(null)
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error taking screenshot", e)
            callback(null)
        }
    }
}
