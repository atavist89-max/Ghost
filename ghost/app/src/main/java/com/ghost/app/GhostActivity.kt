package com.ghost.app

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import com.ghost.app.capture.ScreenCaptureManager
import com.ghost.app.inference.InferenceEngine
import com.ghost.app.inference.ModelValidator
import com.ghost.app.ui.GhostWindowManager
import com.ghost.app.utils.MemoryManager
import com.ghost.app.utils.PermissionChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Main entry point for Ghost application.
 * Orchestrates screen capture, model validation, inference, and UI.
 */
class GhostActivity : Activity() {

    companion object {
        private const val TAG = "GhostActivity"
        const val ACTION_TRIGGER = "com.ghost.app.ACTION_TRIGGER"
        private const val REQUEST_MEDIA_PROJECTION = 1001
    }

    // State flows for UI updates
    private val _responseText = MutableStateFlow("")
    private val _isGenerating = MutableStateFlow(false)

    // Coroutine scope for this activity
    private val mainScope = CoroutineScope(Dispatchers.Main)

    // Managers
    private lateinit var screenCaptureManager: ScreenCaptureManager
    private lateinit var windowManager: GhostWindowManager
    private var inferenceEngine: InferenceEngine? = null

    // Captured screen state
    private var capturedBitmap: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate")

        // Initialize managers
        screenCaptureManager = ScreenCaptureManager(this)
        windowManager = GhostWindowManager(this)

        // Check permissions first
        if (!checkAndRequestPermissions()) {
            finish()
            return
        }

        // Validate model file
        if (!validateModel()) {
            finish()
            return
        }

        // Initialize inference engine
        initializeInferenceEngine()
    }

    /**
     * Check and request required permissions.
     */
    private fun checkAndRequestPermissions(): Boolean {
        val (hasStorage, hasOverlay) = PermissionChecker.checkAllPermissions(this)

        if (!hasStorage) {
            Log.i(TAG, "Requesting MANAGE_EXTERNAL_STORAGE permission")
            return PermissionChecker.requestManageExternalStorage(this)
        }

        if (!hasOverlay) {
            Log.i(TAG, "Requesting SYSTEM_ALERT_WINDOW permission")
            return PermissionChecker.requestOverlayPermission(this)
        }

        return true
    }

    /**
     * Validate model file exists and is valid.
     */
    private fun validateModel(): Boolean {
        return ModelValidator.validateModelWithFeedback(this)
    }

    /**
     * Initialize the inference engine.
     */
    private fun initializeInferenceEngine() {
        inferenceEngine = InferenceEngine(this).apply {
            initialize { success, error ->
                if (success) {
                    Log.i(TAG, "Inference engine ready")
                    // Start screen capture after engine is ready
                    startScreenCapture()
                } else {
                    Log.e(TAG, "Failed to initialize inference: $error")
                    Toast.makeText(
                        this@GhostActivity,
                        "Failed to load model: $error",
                        Toast.LENGTH_LONG
                    ).show()
                    finish()
                }
            }
        }
    }

    /**
     * Start the screen capture process.
     */
    private fun startScreenCapture() {
        Log.i(TAG, "Starting screen capture")
        val intent = screenCaptureManager.createCaptureIntent()
        startActivityForResult(intent, REQUEST_MEDIA_PROJECTION)
    }

    /**
     * Handle activity result for MediaProjection and other requests.
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        when (requestCode) {
            REQUEST_MEDIA_PROJECTION -> {
                handleCaptureResult(resultCode, data)
            }
        }
    }

    /**
     * Handle the result from MediaProjection permission dialog.
     */
    private fun handleCaptureResult(resultCode: Int, data: Intent?) {
        if (resultCode != RESULT_OK || data == null) {
            Log.w(TAG, "Screen capture permission denied")
            Toast.makeText(this, "Screen capture permission required", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Start capture
        val result = androidx.activity.result.ActivityResult(resultCode, data)
        screenCaptureManager.startCapture(result) { bitmap ->
            if (bitmap != null) {
                Log.i(TAG, "Screen captured successfully")
                capturedBitmap = bitmap
                showGhostWindow()
            } else {
                Log.e(TAG, "Failed to capture screen")
                Toast.makeText(this, "Failed to capture screen", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    /**
     * Show the floating PiP window.
     */
    private fun showGhostWindow() {
        windowManager.showWindow(
            capturedBitmap = capturedBitmap,
            responseText = _responseText.value,
            isGenerating = _isGenerating.value,
            onSendQuery = { query -> handleQuery(query) },
            onClose = { closeAndCleanup() }
        )
    }

    /**
     * Handle user query and run inference.
     */
    private fun handleQuery(query: String) {
        Log.i(TAG, "User query: $query")

        _responseText.value = ""
        _isGenerating.value = true

        val bitmap = capturedBitmap ?: run {
            _responseText.value = "Error: No screenshot available"
            _isGenerating.value = false
            return
        }

        inferenceEngine?.analyzeImage(
            bitmap = bitmap,
            query = query,
            onToken = { token ->
                mainScope.launch {
                    _responseText.value += token
                    updateWindowContent()
                }
            },
            onComplete = {
                mainScope.launch {
                    _isGenerating.value = false
                    updateWindowContent()
                }
            },
            onError = { error ->
                mainScope.launch {
                    _responseText.value = "Error: $error"
                    _isGenerating.value = false
                    updateWindowContent()
                }
            }
        )
    }

    /**
     * Update window content with current state.
     */
    private fun updateWindowContent() {
        // Recreate window with updated state
        // In a production app, we'd use a proper state management solution
        if (windowManager.isShowing()) {
            // For now, we rely on the next query to update
            // A more sophisticated approach would use a ViewModel
        }
    }

    /**
     * Close the window and clean up all resources.
     */
    private fun closeAndCleanup() {
        Log.i(TAG, "Closing Ghost and cleaning up")

        // Close window
        windowManager.closeWindow()

        // Stop capture if still running
        screenCaptureManager.stopCapture()

        // Release inference engine
        inferenceEngine?.close()
        inferenceEngine = null

        // Aggressive memory cleanup
        MemoryManager.releaseAll()

        // Finish activity
        finish()
    }

    override fun onStop() {
        super.onStop()
        Log.d(TAG, "onStop")

        if (!isChangingConfigurations) {
            closeAndCleanup()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")

        // Ensure inference engine is closed
        inferenceEngine?.close()
        inferenceEngine = null

        // Ensure window is removed
        if (windowManager.isShowing()) {
            windowManager.closeWindow()
        }

        // Ensure capture is stopped
        screenCaptureManager.stopCapture()

        // Recycle bitmap
        capturedBitmap?.recycle()
        capturedBitmap = null

        // Memory cleanup
        MemoryManager.releaseAll()

        Log.i(TAG, "Inference engine released")
    }

    override fun onBackPressed() {
        closeAndCleanup()
    }
}
