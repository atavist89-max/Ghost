package com.ghost.app

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.HardwareBuffer
import android.hardware.display.DisplayManager
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
            // Use API 30+ takeScreenshot with default display
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                executor,
                { screenshotResult ->
                    if (screenshotResult == null) {
                        Log.e(TAG, "ScreenshotResult is null")
                        callback(null)
                        return@takeScreenshot
                    }
                    
                    val hardwareBuffer = screenshotResult.hardwareBuffer
                    if (hardwareBuffer == null) {
                        Log.e(TAG, "HardwareBuffer is null")
                        callback(null)
                        return@takeScreenshot
                    }
                    
                    try {
                        // Convert HardwareBuffer to Bitmap
                        val bitmap = hardwareBufferToBitmap(hardwareBuffer)
                        Log.i(TAG, "Screenshot captured: ${bitmap?.width}x${bitmap?.height}")
                        callback(bitmap)
                    } finally {
                        // CRITICAL: Always close HardwareBuffer to prevent memory leaks
                        hardwareBuffer.close()
                        Log.d(TAG, "HardwareBuffer closed")
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error taking screenshot", e)
            callback(null)
        }
    }

    /**
     * Convert HardwareBuffer to Bitmap.
     * HardwareBuffer must be closed after this operation.
     */
    private fun hardwareBufferToBitmap(hardwareBuffer: HardwareBuffer): Bitmap? {
        return try {
            val width = hardwareBuffer.width
            val height = hardwareBuffer.height
            
            // Create bitmap with ARGB_8888 config
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            
            // Copy pixels from HardwareBuffer to Bitmap
            // Note: HardwareBuffer doesn't have a direct copy method, 
            // so we use the bitmap's copyPixelsFromBuffer approach
            // The HardwareBuffer needs to be converted to a usable format
            
            // For API 30+, we can use Bitmap.copyPixelsFromBuffer with the HardwareBuffer
            // However, HardwareBuffer requires special handling
            // The actual implementation uses HardwareBuffer's built-in bitmap creation
            
            // Alternative approach: use the hardwareBuffer directly via Bitmap.wrapHardwareBuffer
            val wrappedBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, null)
            if (wrappedBitmap != null) {
                // Copy to a mutable bitmap
                val mutableBitmap = wrappedBitmap.copy(Bitmap.Config.ARGB_8888, true)
                wrappedBitmap.recycle()
                mutableBitmap
            } else {
                Log.e(TAG, "Failed to wrap HardwareBuffer")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error converting HardwareBuffer to Bitmap", e)
            null
        }
    }
}
