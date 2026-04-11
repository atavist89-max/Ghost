package com.ghost.app.ui

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
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.ghost.app.R

/**
 * Manages the floating PiP window using WindowManager.
 * Handles window creation, positioning, drag gestures, and cleanup.
 */
class GhostWindowManager(private val activity: Activity) {

    companion object {
        private const val TAG = "GhostWindow"

        // Window dimensions in dp
        private const val WINDOW_WIDTH_DP = 340
        private const val WINDOW_HEIGHT_DP = 600
        private const val WINDOW_MARGIN_TOP_DP = 88
        private const val WINDOW_MARGIN_END_DP = 24
    }

    private val windowManager = activity.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var windowView: View? = null
    private var composeView: ComposeView? = null

    // Window layout parameters
    private val windowParams: WindowManager.LayoutParams by lazy {
        createWindowParams()
    }

    private var onCloseCallback: (() -> Unit)? = null

    /**
     * Create window layout parameters.
     */
    private fun createWindowParams(): WindowManager.LayoutParams {
        val displayMetrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(displayMetrics)

        // Convert dp to pixels
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
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = marginEndPx
            y = marginTopPx
        }
    }

    /**
     * Show the floating PiP window with Compose UI.
     */
    fun showWindow(
        capturedBitmap: Bitmap?,
        responseText: String,
        isGenerating: Boolean,
        onSendQuery: (String) -> Unit,
        onClose: () -> Unit
    ) {
        if (windowView != null) {
            Log.w(TAG, "Window already showing, ignoring show request")
            return
        }

        onCloseCallback = onClose

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
                    onClose = { closeWindow() }
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
                onClose = { closeWindow() }
            )
            setOnTouchListener(dragHandler)
        }

        // Add to window manager
        try {
            windowManager.addView(windowView, windowParams)
            Log.i(TAG, "Ghost window shown")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show window", e)
            cleanup()
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
                // In a real implementation, we'd use a ViewModel or state holder
                // For now, the window is recreated when content changes significantly
            }
        }
    }

    /**
     * Update window position.
     */
    fun updatePosition(x: Int, y: Int) {
        windowParams.x = x
        windowParams.y = y
        windowView?.let {
            try {
                windowManager.updateViewLayout(it, windowParams)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update window position", e)
            }
        }
    }

    /**
     * Close and remove the window.
     */
    fun closeWindow() {
        Log.i(TAG, "Closing Ghost window")

        // Notify callback
        onCloseCallback?.invoke()
        onCloseCallback = null

        // Remove from window manager
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
    }

    /**
     * Check if window is currently showing.
     */
    fun isShowing(): Boolean {
        return windowView != null && windowView?.parent != null
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
