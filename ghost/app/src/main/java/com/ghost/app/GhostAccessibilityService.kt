package com.ghost.app

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.hardware.HardwareBuffer
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import com.ghost.app.utils.DebugLogger
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
        val msg = "AccessibilityService connected"
        Log.i(TAG, msg)
        DebugLogger.i(TAG, msg)
        _instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not needed for screenshot functionality
        // Required override - leave empty
    }

    override fun onInterrupt() {
        // Required override - leave empty
        val msg = "AccessibilityService interrupted"
        Log.w(TAG, msg)
        DebugLogger.w(TAG, msg)
    }

    override fun onDestroy() {
        super.onDestroy()
        val msg = "AccessibilityService destroyed"
        Log.i(TAG, msg)
        DebugLogger.i(TAG, msg)
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
        val msg = "Requesting screenshot via AccessibilityService"
        Log.d(TAG, msg)
        DebugLogger.d(TAG, msg)
        
        try {
            // Use API 30+ takeScreenshot with proper callback interface
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                executor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshotResult: ScreenshotResult) {
                        val hardwareBuffer = screenshotResult.hardwareBuffer
                        val colorSpace = screenshotResult.colorSpace
                        
                        DebugLogger.d(TAG, "ScreenshotResult received, hardwareBuffer=${hardwareBuffer != null}")
                        
                        if (hardwareBuffer == null) {
                            val errorMsg = "HardwareBuffer is null"
                            Log.e(TAG, errorMsg)
                            DebugLogger.e(TAG, errorMsg)
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
                                val successMsg = "Screenshot captured: ${mutableBitmap.width}x${mutableBitmap.height}, " +
                                        "config=${mutableBitmap.config}, byteCount=${mutableBitmap.byteCount / 1024}KB"
                                Log.i(TAG, successMsg)
                                DebugLogger.i(TAG, successMsg)
                                callback(mutableBitmap)
                            } else {
                                val errorMsg = "Bitmap.wrapHardwareBuffer returned null"
                                Log.e(TAG, errorMsg)
                                DebugLogger.e(TAG, errorMsg)
                                callback(null)
                            }
                        } finally {
                            // CRITICAL: Always close HardwareBuffer to prevent memory leaks
                            hardwareBuffer.close()
                            val closeMsg = "HardwareBuffer closed"
                            Log.d(TAG, closeMsg)
                            DebugLogger.d(TAG, closeMsg)
                        }
                    }
                    
                    override fun onFailure(errorCode: Int) {
                        val errorMsg = "Screenshot failed with error code: $errorCode"
                        Log.e(TAG, errorMsg)
                        DebugLogger.e(TAG, errorMsg)
                        callback(null)
                    }
                }
            )
        } catch (e: Exception) {
            val errorMsg = "Error taking screenshot: ${e.message}"
            Log.e(TAG, errorMsg, e)
            DebugLogger.e(TAG, errorMsg, e)
            callback(null)
        }
    }
}
