package com.ghost.app

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import com.ghost.app.inference.InferenceEngine
import com.ghost.app.ui.GhostInterface
import com.ghost.app.ui.GhostWindowManager
import com.ghost.app.ui.theme.GhostTheme
import com.ghost.app.utils.DebugLogger
import com.ghost.app.utils.MemoryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.ComposeView
import android.view.View
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * PiP Chat Activity - Shows floating overlay window instead of full-screen.
 * 
 * CRITICAL CONSTRAINT: Only modifies UI layer. The double-tap Side Key trigger,
 * AccessibilityService screenshot capture, and inference engine remain untouched.
 * 
 * Architecture:
 * - Activity is transparent (no setContent with full-screen Compose)
 * - Uses GhostWindowManager to add PiP overlay via WindowManager.addView()
 * - PiP slides in from right edge with spring physics animation
 * - Cyberpunk terminal aesthetic: phosphor green, CRT glow, scanlines
 */
class ChatActivity : ComponentActivity() {

    companion object {
        private const val TAG = "ChatActivity"
        const val EXTRA_SCREENSHOT_BYTES = "screenshot_bytes"
        
        fun createIntent(activity: Activity, bitmap: Bitmap): Intent {
            val intent = Intent(activity, ChatActivity::class.java)
            val stream = java.io.ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
            intent.putExtra(EXTRA_SCREENSHOT_BYTES, stream.toByteArray())
            return intent
        }
    }

    private var inferenceEngine: InferenceEngine? = null
    private val mainScope = CoroutineScope(Dispatchers.Main)
    
    private var capturedBitmap: Bitmap? = null
    private val _responseText = mutableStateOf("")
    private val _isGenerating = mutableStateOf(false)
    private val _isEngineReady = mutableStateOf(false)

    // WindowManager overlay components
    private var windowManager: WindowManager? = null
    private var windowView: View? = null
    private var composeView: ComposeView? = null
    private val windowParams: WindowManager.LayoutParams by lazy { createWindowParams() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate - PiP mode")
        
        // Make activity transparent - no UI here, just the PiP overlay
        makeActivityTransparent()
        
        // Get screenshot from intent
        val bytes = intent.getByteArrayExtra(EXTRA_SCREENSHOT_BYTES)
        if (bytes != null) {
            capturedBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            val msg = "Screenshot loaded: ${capturedBitmap?.width}x${capturedBitmap?.height}"
            Log.d(TAG, msg)
            DebugLogger.d(TAG, msg)
        } else {
            DebugLogger.e(TAG, "No screenshot bytes in intent!")
        }
        
        // Initialize inference engine
        initializeEngine()
        
        // Show PiP window immediately
        showPiPWindow()
    }
    
    private fun makeActivityTransparent() {
        // Activity is transparent - no content view set
        // The PiP window is shown via WindowManager.addView()
        window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setDimAmount(0f)
        }
    }
    
    private fun createWindowParams(): WindowManager.LayoutParams {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = android.util.DisplayMetrics()
        wm.defaultDisplay.getMetrics(metrics)
        
        val density = metrics.density
        val widthPx = (340 * density).toInt()
        val heightPx = (600 * density).toInt()
        val marginTopPx = (88 * density).toInt()
        val marginEndPx = (24 * density).toInt()
        
        return WindowManager.LayoutParams(
            widthPx,
            heightPx,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
            },
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = marginEndPx
            y = marginTopPx
            // Start off-screen for slide-in animation
            this.x = widthPx + marginEndPx
        }
    }
    
    private fun showPiPWindow() {
        if (windowView != null) {
            Log.w(TAG, "PiP window already showing")
            return
        }
        
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        
        // Create lifecycle owner for Compose
        val lifecycleOwner = GhostLifecycleOwner()
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        
        // Create Compose view with cyberpunk UI
        composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            
            setContent {
                GhostTheme {
                    GhostInterface(
                        capturedBitmap = capturedBitmap,
                        responseText = _responseText.value,
                        isGenerating = _isGenerating.value,
                        isEngineReady = _isEngineReady.value,
                        onSendQuery = { query -> handleQuery(query) },
                        onClose = { closePiPAndFinish() },
                        onDebugClick = { /* Debug gated by BuildConfig.DEBUG in UI */ }
                    )
                }
            }
        }
        
        // Create container frame
        windowView = FrameLayout(this).apply {
            addView(composeView)
            
            // Set up drag/swipe to close
            val dragHandler = com.ghost.app.ui.DragHandler(
                params = windowParams,
                windowManager = windowManager!!,
                onClose = { closePiPAndFinish() }
            )
            setOnTouchListener(dragHandler)
        }
        
        // Add to window manager
        try {
            windowManager?.addView(windowView, windowParams)
            Log.i(TAG, "PiP window added, animating in...")
            
            // Animate slide-in from right
            animateSlideIn()
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show PiP window", e)
            finish()
        }
    }
    
    private fun animateSlideIn() {
        val density = resources.displayMetrics.density
        val marginEndPx = (24 * density).toInt()
        val targetX = marginEndPx
        val startX = windowParams.x
        
        // Spring animation for slide-in
        val springAnimation = android.view.ViewAnimationUtils.createCircularReveal(
            windowView!!,
            windowView!!.width,
            windowView!!.height / 2,
            0f,
            windowView!!.width.toFloat()
        )
        
        // Use ValueAnimator for slide-in with spring physics
        android.animation.ValueAnimator.ofInt(startX, targetX).apply {
            duration = 400
            interpolator = android.view.animation.OvershootInterpolator(0.8f)
            addUpdateListener { animator ->
                windowParams.x = animator.animatedValue as Int
                try {
                    windowManager?.updateViewLayout(windowView, windowParams)
                } catch (e: Exception) {
                    // View might be removed
                }
            }
            start()
        }
    }
    
    private fun animateSlideOutAndClose(onComplete: () -> Unit) {
        val screenWidth = resources.displayMetrics.widthPixels
        val startX = windowParams.x
        
        android.animation.ValueAnimator.ofInt(startX, screenWidth).apply {
            duration = 300
            interpolator = android.view.animation.AccelerateInterpolator()
            addUpdateListener { animator ->
                windowParams.x = animator.animatedValue as Int
                try {
                    windowManager?.updateViewLayout(windowView, windowParams)
                } catch (e: Exception) {
                    // View might be removed
                }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    onComplete()
                }
            })
            start()
        }
    }
    
    private fun closePiPAndFinish() {
        if (windowView == null) {
            finish()
            return
        }
        
        animateSlideOutAndClose {
            cleanupWindow()
            finish()
        }
    }
    
    private fun cleanupWindow() {
        try {
            windowView?.let {
                windowManager?.removeViewImmediate(it)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing window", e)
        }
        windowView = null
        composeView = null
    }
    
    private fun initializeEngine() {
        inferenceEngine = InferenceEngine(this).apply {
            initialize { success, error ->
                if (success) {
                    Log.i(TAG, "Inference engine ready")
                    DebugLogger.i(TAG, "Inference engine ready")
                    _isEngineReady.value = true
                } else {
                    Log.e(TAG, "Failed to initialize inference: $error")
                    DebugLogger.e(TAG, "Failed to initialize inference: $error")
                    _responseText.value = "Error: Failed to load model: $error"
                }
            }
        }
    }
    
    private fun handleQuery(query: String) {
        Log.i(TAG, "User query: $query")
        DebugLogger.i(TAG, "User query: $query")
        
        if (capturedBitmap == null) {
            _responseText.value = "Error: No screenshot available"
            DebugLogger.e(TAG, "No screenshot available!")
            return
        }
        
        val bitmap = capturedBitmap!!
        if (bitmap.isRecycled) {
            Log.e(TAG, "Bitmap is recycled!")
            DebugLogger.e(TAG, "Bitmap is recycled!")
            _responseText.value = "Error: Screenshot was recycled"
            return
        }
        
        val bitmapInfo = "Bitmap: ${bitmap.width}x${bitmap.height}, config=${bitmap.config}, " +
                "byteCount=${bitmap.byteCount / 1024}KB"
        Log.i(TAG, "Sending bitmap to inference: $bitmapInfo")
        DebugLogger.i(TAG, bitmapInfo)
        
        _responseText.value = ""
        _isGenerating.value = true
        
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
                    DebugLogger.i(TAG, "Inference complete")
                }
            },
            onError = { error ->
                mainScope.launch {
                    _responseText.value = "Error: $error"
                    _isGenerating.value = false
                    DebugLogger.e(TAG, "Inference error: $error")
                }
            }
        )
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
        
        cleanupWindow()
        
        inferenceEngine?.close()
        inferenceEngine = null
        
        capturedBitmap?.recycle()
        capturedBitmap = null
        
        MemoryManager.releaseAll()
    }
    
    override fun onBackPressed() {
        closePiPAndFinish()
    }
    
    /**
     * Simple lifecycle owner for Compose in floating window.
     */
    private class GhostLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner {
        private val lifecycleRegistry = LifecycleRegistry(this)
        private val savedStateRegistryController = SavedStateRegistryController.create(this)
        
        override val lifecycle: Lifecycle get() = lifecycleRegistry
        override val savedStateRegistry: SavedStateRegistry get() =
            savedStateRegistryController.savedStateRegistry
        
        init {
            savedStateRegistryController.performAttach()
        }
        
        fun handleLifecycleEvent(event: Lifecycle.Event) {
            lifecycleRegistry.handleLifecycleEvent(event)
        }
    }
}
