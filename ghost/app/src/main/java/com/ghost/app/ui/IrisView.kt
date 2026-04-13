package com.ghost.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.animation.doOnEnd
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce

/**
 * Iris - Mechanical bracket-shaped eyes with cursor-tracking pupils.
 * 
 * Dimensions: 56dp x 32dp (two 20dp x 32dp eyes with 8dp gap)
 * Features:
 * - Bracket-shaped frames [ ] with phosphor glow
 * - Cursor-tracking pupils with spring physics
 * - 7 expression states with mechanical animations
 * - 60fps hardware accelerated rendering
 */
class IrisView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class State {
        IDLE,       // Slow blink, breathing scale
        LISTENING,  // Tracks cursor horizontally
        FOCUSED,    // Pupils morph to vertical lines
        THINKING,   // Scanning animation
        ANALYZING,  // Bar pupils, rapid pulse
        SUCCESS,    // Checkmark pupils
        CONFUSED    // Question mark, wobble
    }

    companion object {
        // Colors
        private const val PHOSPHOR_GREEN = 0xFF39FF14.toInt()
        private const val PHOSPHOR_DIM = 0xFF2B8C1A.toInt()
        private const val GUNMETAL_BG = 0xFF0A0F0A.toInt()
        private const val ERROR_RED = 0xFFFF4444.toInt()
        
        // Dimensions (dp converted to pixels in init)
        private const val EYE_WIDTH_DP = 20f
        private const val EYE_HEIGHT_DP = 32f
        private const val EYE_GAP_DP = 8f
        private const val BORDER_WIDTH_DP = 2f
        private const val PUPIL_SIZE_DP = 4f
        private const val TOTAL_WIDTH_DP = 56f // 20 + 8 + 20 + 8 padding
        private const val TOTAL_HEIGHT_DP = 32f
    }

    // Paints
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PHOSPHOR_GREEN
        style = Paint.Style.STROKE
        strokeWidth = BORDER_WIDTH_DP
        strokeCap = Paint.Cap.SQUARE
    }
    
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PHOSPHOR_GREEN
        style = Paint.Style.STROKE
        strokeWidth = BORDER_WIDTH_DP + 2f
        maskFilter = BlurMaskFilter(6f, BlurMaskFilter.Blur.NORMAL)
        alpha = 128
    }
    
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GUNMETAL_BG
        style = Paint.Style.FILL
        alpha = 217 // 85% opacity
    }
    
    private val pupilPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PHOSPHOR_GREEN
        style = Paint.Style.FILL
    }
    
    private val pupilGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PHOSPHOR_GREEN
        style = Paint.Style.FILL
        maskFilter = BlurMaskFilter(4f, BlurMaskFilter.Blur.NORMAL)
        alpha = 180
    }
    
    private val shutterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GUNMETAL_BG
        style = Paint.Style.FILL
    }

    // Dimensions in pixels
    private val density = resources.displayMetrics.density
    private val eyeWidth = EYE_WIDTH_DP * density
    private val eyeHeight = EYE_HEIGHT_DP * density
    private val eyeGap = EYE_GAP_DP * density
    private val borderWidth = BORDER_WIDTH_DP * density
    private val pupilSize = PUPIL_SIZE_DP * density
    
    // Eye bounds
    private val leftEyeBounds = RectF()
    private val rightEyeBounds = RectF()
    
    // State
    private var currentState = State.IDLE
    private var cursorPercent = 0.5f // 0.0 = left, 1.0 = right
    private var targetPupilX = 0f
    private var currentPupilX = 0f
    
    // Animation values
    private var scale = 1f
    private var blinkProgress = 0f // 0.0 = open, 1.0 = closed
    private var glowIntensity = 0.5f
    private var pupilMorph = 0f // 0.0 = dot, 1.0 = line
    private var scanOffset = 0f
    private var tiltAngle = 0f
    private var wobbleOffset = 0f
    
    // Animators
    private var blinkAnimator: ValueAnimator? = null
    private var breatheAnimator: ValueAnimator? = null
    private var scanAnimator: ValueAnimator? = null
    private var pulseAnimator: ValueAnimator? = null
    private val pupilSpring: SpringAnimation
    
    // Spring animation for cursor tracking
    private val springForce = SpringForce().apply {
        stiffness = SpringForce.STIFFNESS_MEDIUM
        dampingRatio = SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY
    }

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        
        // Initialize spring animation for pupil
        pupilSpring = SpringAnimation(FloatValueHolder()).apply {
            spring = springForce
            addUpdateListener { _, value, _ ->
                currentPupilX = value
                invalidate()
            }
        }
        
        startIdleAnimations()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        
        val leftX = (w - (eyeWidth * 2 + eyeGap)) / 2f
        val topY = (h - eyeHeight) / 2f
        
        leftEyeBounds.set(leftX, topY, leftX + eyeWidth, topY + eyeHeight)
        rightEyeBounds.set(leftX + eyeWidth + eyeGap, topY, 
                          leftX + eyeWidth * 2 + eyeGap, topY + eyeHeight)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        canvas.save()
        canvas.scale(scale, scale, width / 2f, height / 2f)
        
        // Draw eyes
        drawEye(canvas, leftEyeBounds, true)
        drawEye(canvas, rightEyeBounds, false)
        
        canvas.restore()
    }

    private fun drawEye(canvas: Canvas, bounds: RectF, isLeft: Boolean) {
        val centerX = bounds.centerX()
        val centerY = bounds.centerY()
        
        canvas.save()
        
        // Apply tilt for certain states
        when (currentState) {
            State.THINKING -> {
                val tilt = if (isLeft) -tiltAngle else tiltAngle
                canvas.rotate(tilt, centerX, centerY)
            }
            State.CONFUSED -> {
                val tilt = if (isLeft) -5f else 5f
                canvas.rotate(tilt + wobbleOffset, centerX, centerY)
            }
            else -> {}
        }
        
        // Draw glow (behind)
        glowPaint.alpha = (glowIntensity * 128).toInt()
        drawBracketShape(canvas, bounds, glowPaint)
        
        // Draw fill
        drawBracketShape(canvas, bounds, fillPaint, fill = true)
        
        // Draw border
        borderPaint.strokeWidth = borderWidth
        drawBracketShape(canvas, bounds, borderPaint)
        
        // Draw pupils (if not fully blinked)
        if (blinkProgress < 0.8f) {
            drawPupil(canvas, bounds, isLeft)
        }
        
        // Draw mechanical shutters for blink
        if (blinkProgress > 0f) {
            drawShutters(canvas, bounds)
        }
        
        canvas.restore()
    }

    private fun drawBracketShape(canvas: Canvas, bounds: RectF, paint: Paint, fill: Boolean = false) {
        val path = Path()
        val inset = borderWidth / 2f
        
        // Left bracket: [ shape (open right)
        // Right bracket: ] shape (open left)
        val isLeft = bounds.left < width / 2f
        
        if (isLeft) {
            // [ shape
            path.moveTo(bounds.right - inset, bounds.top + inset)
            path.lineTo(bounds.left + inset, bounds.top + inset)
            path.lineTo(bounds.left + inset, bounds.bottom - inset)
            path.lineTo(bounds.right - inset, bounds.bottom - inset)
        } else {
            // ] shape
            path.moveTo(bounds.left + inset, bounds.top + inset)
            path.lineTo(bounds.right - inset, bounds.top + inset)
            path.lineTo(bounds.right - inset, bounds.bottom - inset)
            path.lineTo(bounds.left + inset, bounds.bottom - inset)
        }
        
        if (fill) {
            path.close()
            canvas.drawPath(path, paint)
        } else {
            canvas.drawPath(path, paint)
        }
    }

    private fun drawPupil(canvas: Canvas, bounds: RectF, isLeft: Boolean) {
        // Calculate pupil position based on state
        val pupilX = when (currentState) {
            State.LISTENING -> {
                // Track cursor
                val trackRange = eyeWidth * 0.6f
                bounds.centerX() + (cursorPercent - 0.5f) * trackRange
            }
            State.THINKING -> {
                // Scanning motion
                bounds.centerX() + (scanOffset - 0.5f) * eyeWidth * 0.7f
            }
            State.CONFUSED -> {
                // Wobbling center
                bounds.centerX() + wobbleOffset * 2f
            }
            else -> bounds.centerX()
        }
        
        val pupilY = bounds.centerY()
        
        // Apply blink occlusion
        val visibleHeight = eyeHeight * (1f - blinkProgress) * 0.8f
        
        when (currentState) {
            State.FOCUSED, State.ANALYZING -> {
                // Vertical line pupil
                val lineWidth = pupilSize * 0.5f
                val lineHeight = if (currentState == State.ANALYZING) {
                    visibleHeight * 0.9f
                } else {
                    visibleHeight * 0.5f
                }
                
                // Glow
                canvas.drawRoundRect(
                    pupilX - lineWidth - 1f, pupilY - lineHeight / 2f - 1f,
                    pupilX + lineWidth + 1f, pupilY + lineHeight / 2f + 1f,
                    2f, 2f, pupilGlowPaint
                )
                
                // Core
                canvas.drawRoundRect(
                    pupilX - lineWidth, pupilY - lineHeight / 2f,
                    pupilX + lineWidth, pupilY + lineHeight / 2f,
                    1f, 1f, pupilPaint
                )
            }
            State.SUCCESS -> {
                // Checkmark shape (simplified as two lines)
                val checkPaint = Paint(pupilPaint).apply {
                    strokeWidth = pupilSize * 0.6f
                    strokeCap = Paint.Cap.ROUND
                }
                val size = pupilSize * 1.5f
                canvas.drawLine(pupilX - size/2f, pupilY, pupilX - size/6f, pupilY + size/2f, checkPaint)
                canvas.drawLine(pupilX - size/6f, pupilY + size/2f, pupilX + size/2f, pupilY - size/3f, checkPaint)
            }
            State.CONFUSED -> {
                // Question mark (simplified as dot with curve)
                canvas.drawCircle(pupilX, pupilY - pupilSize, pupilSize * 0.6f, pupilPaint)
                val curvePaint = Paint(pupilPaint).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = pupilSize * 0.5f
                }
                canvas.drawArc(
                    pupilX - pupilSize, pupilY - pupilSize * 0.5f,
                    pupilX + pupilSize, pupilY + pupilSize * 1.5f,
                    0f, 180f, false, curvePaint
                )
            }
            else -> {
                // Dot pupil (IDLE, LISTENING, THINKING)
                val dotSize = pupilSize * (1f - pupilMorph * 0.3f)
                
                // Glow
                canvas.drawCircle(pupilX, pupilY, dotSize * 1.5f, pupilGlowPaint)
                
                // Core
                canvas.drawCircle(pupilX, pupilY, dotSize, pupilPaint)
            }
        }
    }

    private fun drawShutters(canvas: Canvas, bounds: RectF) {
        val shutterHeight = bounds.height() * blinkProgress * 0.6f
        
        // Upper shutter
        canvas.drawRect(
            bounds.left, bounds.top,
            bounds.right, bounds.top + shutterHeight,
            shutterPaint
        )
        
        // Lower shutter
        canvas.drawRect(
            bounds.left, bounds.bottom - shutterHeight * 0.7f,
            bounds.right, bounds.bottom,
            shutterPaint
        )
    }

    // Public API
    
    fun setState(newState: State) {
        if (currentState == newState) return
        
        currentState = newState
        stopAllAnimations()
        
        when (newState) {
            State.IDLE -> startIdleAnimations()
            State.LISTENING -> {
                glowIntensity = 0.7f
                pupilMorph = 0f
            }
            State.FOCUSED -> {
                glowIntensity = 0.9f
                animatePupilMorph(1f)
            }
            State.THINKING -> startScanAnimation()
            State.ANALYZING -> startPulseAnimation()
            State.SUCCESS -> {
                glowIntensity = 1f
                performBlink()
                postDelayed({ setState(State.IDLE) }, 2000)
            }
            State.CONFUSED -> {
                startWobbleAnimation()
                performRapidBlink()
            }
        }
        
        invalidate()
    }

    fun setCursorPosition(percent: Float) {
        cursorPercent = percent.coerceIn(0f, 1f)
        if (currentState == State.LISTENING) {
            invalidate()
        }
    }

    // Animation methods
    
    private fun startIdleAnimations() {
        // Breathing scale
        breatheAnimator = ValueAnimator.ofFloat(0.96f, 1f).apply {
            duration = 2000
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { scale = it.animatedValue as Float }
            start()
        }
        
        // Slow blink every 3-4 seconds
        postDelayed({ performBlink() }, 3000)
        
        glowIntensity = 0.4f
    }

    private fun performBlink() {
        blinkAnimator?.cancel()
        blinkAnimator = ValueAnimator.ofFloat(0f, 1f, 0f).apply {
            duration = 300
            interpolator = LinearInterpolator()
            addUpdateListener { blinkProgress = it.animatedValue as Float }
            doOnEnd {
                if (currentState == State.IDLE) {
                    postDelayed({ performBlink() }, 3000 + (Math.random() * 1000).toLong())
                }
            }
            start()
        }
    }

    private fun performRapidBlink() {
        var count = 0
        val blinkRunnable = object : Runnable {
            override fun run() {
                if (count < 3) {
                    blinkAnimator?.cancel()
                    blinkAnimator = ValueAnimator.ofFloat(0f, 1f, 0f).apply {
                        duration = 150
                        interpolator = LinearInterpolator()
                        addUpdateListener { blinkProgress = it.animatedValue as Float }
                        start()
                    }
                    count++
                    postDelayed(this, 200)
                }
            }
        }
        post(blinkRunnable)
    }

    private fun startScanAnimation() {
        scanAnimator = ValueAnimator.ofFloat(0f, 1f, 0f).apply {
            duration = 800
            repeatMode = ValueAnimator.RESTART
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { 
                scanOffset = it.animatedValue as Float
                tiltAngle = (scanOffset - 0.5f) * 10f
                invalidate()
            }
            start()
        }
    }

    private fun startPulseAnimation() {
        pulseAnimator = ValueAnimator.ofFloat(0.8f, 1f).apply {
            duration = 200
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { 
                glowIntensity = it.animatedValue as Float
                scale = 0.95f + (glowIntensity - 0.8f) * 0.25f
                invalidate()
            }
            start()
        }
    }

    private fun startWobbleAnimation() {
        scanAnimator = ValueAnimator.ofFloat(-1f, 1f, -1f).apply {
            duration = 600
            repeatMode = ValueAnimator.RESTART
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { 
                wobbleOffset = it.animatedValue as Float * 3f
                invalidate()
            }
            start()
        }
    }

    private fun animatePupilMorph(target: Float) {
        ValueAnimator.ofFloat(pupilMorph, target).apply {
            duration = 200
            interpolator = LinearInterpolator()
            addUpdateListener { pupilMorph = it.animatedValue as Float }
            start()
        }
    }

    private fun stopAllAnimations() {
        breatheAnimator?.cancel()
        blinkAnimator?.cancel()
        scanAnimator?.cancel()
        pulseAnimator?.cancel()
        removeCallbacks(null)
        
        blinkProgress = 0f
        tiltAngle = 0f
        wobbleOffset = 0f
        scanOffset = 0f
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAllAnimations()
    }
}
