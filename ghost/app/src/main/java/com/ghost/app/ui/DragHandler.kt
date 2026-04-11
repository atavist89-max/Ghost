package com.ghost.app.ui

import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.abs

/**
 * Handles drag gestures for the floating PiP window.
 */
class DragHandler(
    private val params: WindowManager.LayoutParams,
    private val windowManager: WindowManager,
    private val onClose: () -> Unit
) : View.OnTouchListener {

    companion object {
        // Threshold for swipe-to-close (in pixels)
        private const val SWIPE_THRESHOLD = 150

        // Threshold to detect click vs drag
        private const val CLICK_THRESHOLD = 10
    }

    private var initialX = 0
    private var initialY = 0
    private var touchX = 0f
    private var touchY = 0f
    private var isDragging = false

    override fun onTouch(view: View, event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = params.x
                initialY = params.y
                touchX = event.rawX
                touchY = event.rawY
                isDragging = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val deltaX = (event.rawX - touchX).toInt()
                val deltaY = (event.rawY - touchY).toInt()

                // Check if this is a drag or a click
                if (abs(deltaX) > CLICK_THRESHOLD || abs(deltaY) > CLICK_THRESHOLD) {
                    isDragging = true
                }

                // Update window position
                params.x = initialX + deltaX
                params.y = initialY + deltaY

                try {
                    windowManager.updateViewLayout(view, params)
                } catch (e: Exception) {
                    // View might have been removed
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                val deltaX = (event.rawX - touchX).toInt()
                val deltaY = (event.rawY - touchY).toInt()

                // Check for swipe-to-close gesture
                val totalDelta = abs(deltaX) + abs(deltaY)
                if (totalDelta > SWIPE_THRESHOLD && isDragging) {
                    // Check if swiped off-screen
                    val screenWidth = view.resources.displayMetrics.widthPixels
                    val screenHeight = view.resources.displayMetrics.heightPixels

                    val viewX = params.x + view.width / 2
                    val viewY = params.y + view.height / 2

                    if (viewX < -view.width / 2 || viewX > screenWidth + view.width / 2 ||
                        viewY < -view.height / 2 || viewY > screenHeight + view.height / 2
                    ) {
                        onClose()
                        return true
                    }
                }

                // If not a drag, let click handlers process it
                return isDragging
            }

            MotionEvent.ACTION_CANCEL -> {
                return true
            }
        }

        return false
    }

    /**
     * Reset drag state.
     */
    fun reset() {
        isDragging = false
    }
}

/**
 * Compose-friendly drag state holder.
 */
class DragState {
    var offsetX by androidx.compose.runtime.mutableFloatStateOf(0f)
    var offsetY by androidx.compose.runtime.mutableFloatStateOf(0f)
    var isDragging by androidx.compose.runtime.mutableStateOf(false)

    private var initialOffsetX = 0f
    private var initialOffsetY = 0f

    fun onDragStart() {
        isDragging = true
        initialOffsetX = offsetX
        initialOffsetY = offsetY
    }

    fun onDrag(deltaX: Float, deltaY: Float) {
        offsetX = initialOffsetX + deltaX
        offsetY = initialOffsetY + deltaY
    }

    fun onDragEnd() {
        isDragging = false
    }

    fun reset() {
        offsetX = 0f
        offsetY = 0f
        isDragging = false
    }
}
