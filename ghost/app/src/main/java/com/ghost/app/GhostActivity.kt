package com.ghost.app

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.provider.Settings
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
    
    // Track initialization state
    private var isInitialized = false
    private var permissionsRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate")
        
        // Initialize managers
        screenCaptureManager = ScreenCaptureManager(this)
        windowManager = GhostWindowManager(this)
    }
    
    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume, isInitialized=$isInitialized, permissionsRequested=$permissionsRequested")
        
        // Check and request permissions on resume (handles return from settings)
        if (!isInitialized) {
            checkPermissionsAndProceed()
        }
    }
    
    /**
     * Check permissions and proceed with initialization
     */
    private fun checkPermissionsAndProceed() {
        val (hasStorage, hasOverlay, hasNotifications) = PermissionChecker.checkAllPermissions(this)
        
        Log.d(TAG, "Permissions: storage=$hasStorage, overlay=$hasOverlay, notifications=$hasNotifications")

        if (!hasStorage) {
            if (!permissionsRequested) {
                Log.i(TAG, "Requesting MANAGE_EXTERNAL_STORAGE permission")
                permissionsRequested = true
                Toast.makeText(this, "Please enable 'All files access' for Ghost", Toast.LENGTH_LONG).show()
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(intent)
            }
            return  // Don't finish, wait for user to return
        }

        if (!hasOverlay) {
            if (!permissionsRequested) {
                Log.i(TAG, "Requesting SYSTEM_ALERT_WINDOW permission")
                permissionsRequested = true
                Toast.makeText(this, "Please enable 'Appear on top' for Ghost", Toast.LENGTH_LONG).show()
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                    data = android.net.Uri.parse("package:$packageName")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(intent)
            }
            return  // Don't finish, wait for user to return
        }

        if (!hasNotifications) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                Log.i(TAG, "Requesting POST_NOTIFICATIONS permission")
                requestPermissions(
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    1003
                )
            }
            return
        }
        
        // All permissions granted, reset flag and proceed
        permissionsRequested = false
        
        // Validate model file
        if (!validateModel()) {
            Log.e(TAG, "Model validation failed")
            finish()
            return
        }
        
        // Start screen capture (this is the first step after permissions)
        if (!isInitialized) {
            isInitialized = true
            startScreenCapture()
        }
    }

    /**
     * Validate model file exists and is valid.
     */
    private fun validateModel(): Boolean {
        return ModelValidator.validateModelWithFeedback(this)
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
        Log.d(TAG, "onActivityResult: requestCode=$requestCode, resultCode=$resultCode")
        
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
        Log.d(TAG, "handleCaptureResult: resultCode=$resultCode, data=$data")
        
        if (resultCode != RESULT_OK) {
            Log.w(TAG, "Screen capture permission denied or cancelled")
            Toast.makeText(this, "Screen capture permission required", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        if (data == null) {
            Log.e(TAG, "Screen capture result data is null")
            Toast.makeText(this, "Screen capture failed: no data", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Start capture
        val result = androidx.activity.result.ActivityResult(resultCode, data)
        screenCaptureManager.startCapture(this, result) { bitmap ->
            if (bitmap != null) {
                Log.i(TAG, "Screen captured successfully")
                capturedBitmap = bitmap
                // Now initialize inference engine and show window
                initializeInferenceEngine()
            } else {
                Log.e(TAG, "Failed to capture screen - bitmap is null")
                Toast.makeText(this, "Failed to capture screen", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }
    
    /**
     * Initialize the inference engine AFTER screen capture.
     */
    private fun initializeInferenceEngine() {
        inferenceEngine = InferenceEngine(this).apply {
            initialize { success, error ->
                if (success) {
                    Log.i(TAG, "Inference engine ready")
                    showGhostWindow()
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
                }
            },
            onComplete = {
                mainScope.launch {
                    _isGenerating.value = false
                }
            },
            onError = { error ->
                mainScope.launch {
                    _responseText.value = "Error: $error"
                    _isGenerating.value = false
                }
            }
        )
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
