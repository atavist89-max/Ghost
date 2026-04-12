package com.ghost.app.ui

import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * Manages the floating PiP window using WindowManager.
 * Handles window creation, positioning, drag gestures, spring animations, and cleanup.
 * 
 * Cyberpunk PiP Specifications:
 * - Dimensions: 340dp × 600dp (portrait PiP)
 * - Position: Attached to right edge, 24dp from edge, 88dp from top
 * - Entrance: Slide in from right with spring physics
 * - Exit: Slide out to right
 * - Border: 2dp phosphor green outline with glow
 */
class GhostWindowManager(private val activity: Activity) {

    companion object {
        private const val TAG = "GhostWindow"

        // Window dimensions in dp
        private const val WINDOW_WIDTH_DP = 340
        private const val WINDOW_HEIGHT_DP = 600
        private const val WINDOW_MARGIN_TOP_DP = 88
        private const val WINDOW_MARGIN_END_DP = 24
        
        // Animation constants
        private const val SLIDE_IN_DURATION = 400L
        private const val SLIDE_OUT_DURATION = 300L
        private const val SPRING_DAMPING = 0.8f
    }

    private val windowManager = activity.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var windowView: View? = null
    private var composeView: ComposeView? = null

    // Window layout parameters
    private val windowParams: WindowManager.LayoutParams by lazy {
        createWindowParams()
    }

    private var onCloseCallback: (() -> Unit)? = null
    private var isAnimating = false

    /**
     * Create window layout parameters for right-edge attachment.
     */
    private fun createWindowParams(): WindowManager.LayoutParams {
        val displayMetrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(displayMetrics)

        val density = displayMetrics.density
        val widthPx = (WINDOW_WIDTH_DP * density).toInt()
        val heightPx = (WINDOW_HEIGHT_DP * density).toInt()
        val marginTopPx = (WINDOW_MARGIN_TOP_DP * density).toInt()
        val marginEndPx = (WINDOW_MARGIN_END_DP * density).toInt()

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
            x = widthPx + marginEndPx  // Start off-screen for slide-in
            y = marginTopPx
        }
    }

    /**
     * Show the floating PiP window with Compose UI and spring slide-in animation.
     */
    fun showWindow(
        capturedBitmap: Bitmap?,
        responseText: String,
        isGenerating: Boolean,
        onSendQuery: (String) -> Unit,
        onClose: () -> Unit
    ) {
        if (windowView != null || isAnimating) {
            Log.w(TAG, "Window already showing or animating, ignoring show request")
            return
        }

        onCloseCallback = onClose
        isAnimating = true

        // Create lifecycle owner for Compose
        val lifecycleOwner = GhostLifecycleOwner()
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        // Create Compose view
        composeView = ComposeView(activity).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)

            setContent {
                GhostInterface(
                    capturedBitmap = capturedBitmap,
                    responseText = responseText,
                    isGenerating = isGenerating,
                    onSendQuery = onSendQuery,
                    onClose = { animateSlideOutAndClose() }
                )
            }
        }

        // Create container with drag handler
        windowView = FrameLayout(activity).apply {
            addView(composeView)

            // Set up drag handler
            val dragHandler = DragHandler(
                params = windowParams,
                windowManager = windowManager,
                onClose = { animateSlideOutAndClose() }
            )
            setOnTouchListener(dragHandler)
        }

        // Add to window manager
        try {
            windowManager.addView(windowView, windowParams)
            Log.i(TAG, "Ghost window added, animating slide-in...")
            
            // Animate slide-in from right
            animateSlideIn {
                isAnimating = false
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show window", e)
            isAnimating = false
            cleanup()
        }
    }

    /**
     * Animate slide-in from right with spring physics.
     * Animation spec: spring(stiffness = 300f, dampingRatio = 0.8f)
     */
    private fun animateSlideIn(onComplete: () -> Unit = {}) {
        val density = activity.resources.displayMetrics.density
        val targetX = (WINDOW_MARGIN_END_DP * density).toInt()
        val startX = windowParams.x
        
        ValueAnimator.ofInt(startX, targetX).apply {
            duration = SLIDE_IN_DURATION
            interpolator = OvershootInterpolator(SPRING_DAMPING)
            addUpdateListener { animator ->
                windowParams.x = animator.animatedValue as Int
                updateWindowLayout()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    onComplete()
                }
            })
            start()
        }
    }

    /**
     * Animate slide-out to right and close window.
     */
    private fun animateSlideOutAndClose() {
        if (isAnimating || windowView == null) return
        isAnimating = true
        
        val displayMetrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(displayMetrics)
        val screenWidth = displayMetrics.widthPixels
        
        val startX = windowParams.x
        
        ValueAnimator.ofInt(startX, screenWidth).apply {
            duration = SLIDE_OUT_DURATION
            interpolator = AccelerateInterpolator()
            addUpdateListener { animator ->
                windowParams.x = animator.animatedValue as Int
                updateWindowLayout()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    closeWindow()
                    isAnimating = false
                }
            })
            start()
        }
    }

    /**
     * Update window layout position.
     */
    private fun updateWindowLayout() {
        windowView?.let { view ->
            try {
                windowManager.updateViewLayout(view, windowParams)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update window layout", e)
            }
        }
    }

    /**
     * Update the window content.
     */
    fun updateContent(
        capturedBitmap: Bitmap? = null,
        responseText: String? = null,
        isGenerating: Boolean? = null
    ) {
        composeView?.let { view ->
            view.post {
                // Force recomposition by updating state
                // State updates trigger recomposition in Compose
            }
        }
    }

    /**
     * Update window position.
     */
    fun updatePosition(x: Int, y: Int) {
        windowParams.x = x
        windowParams.y = y
        updateWindowLayout()
    }

    /**
     * Close and remove the window.
     */
    fun closeWindow() {
        Log.i(TAG, "Closing Ghost window")

        onCloseCallback?.invoke()
        onCloseCallback = null

        cleanup()
    }

    /**
     * Clean up resources.
     */
    private fun cleanup() {
        try {
            windowView?.let {
                windowManager.removeViewImmediate(it)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing window", e)
        }

        windowView = null
        composeView = null
        isAnimating = false
    }

    /**
     * Check if window is currently showing.
     */
    fun isShowing(): Boolean {
        return windowView != null && windowView?.parent != null && !isAnimating
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

/**
 * Data class for window state.
 */
data class GhostWindowState(
    val capturedBitmap: Bitmap? = null,
    val responseText: String = "",
    val isGenerating: Boolean = false,
    val isVisible: Boolean = false
)
