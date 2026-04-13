package com.ghost.app

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.ghost.app.inference.InferenceEngine
import com.ghost.app.ui.GhostInterface
import androidx.compose.ui.ExperimentalComposeUiApi
import com.ghost.app.ui.theme.GhostTheme
import com.ghost.app.utils.DebugLogger
import com.ghost.app.utils.MemoryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Chat Activity - Transparent PiP-style overlay with keyboard handling.
 * 
 * CRITICAL: This uses setContent with transparent theme (not WindowManager.addView).
 * WindowManager approach requires SYSTEM_ALERT_WINDOW permission which the app doesn't have.
 * 
 * Architecture:
 * - Activity has transparent background (shows apps behind)
 * - PiP UI rendered via Compose setContent (standard Activity approach)
 * - Slide-in animation using Compose animation APIs
 * - Keyboard handling: PiP height squishes to keep Iris and input visible (no offset)
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate - PiP Activity mode")
        
        // Enable edge-to-edge for transparent activity
        enableEdgeToEdge()
        
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
        
        // Set up Compose UI with transparent background and keyboard handling
        setContent {
            GhostTheme {
                ChatScreenPiP(
                    screenshot = capturedBitmap,
                    responseText = _responseText.value,
                    isGenerating = _isGenerating.value,
                    isEngineReady = _isEngineReady.value,
                    onSendQuery = { query -> handleQuery(query) },
                    onClose = { finishAndRemoveTask() }
                )
            }
        }
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
        
        inferenceEngine?.close()
        inferenceEngine = null
        
        capturedBitmap?.recycle()
        capturedBitmap = null
        
        MemoryManager.releaseAll()
    }
    
    override fun onBackPressed() {
        finishAndRemoveTask()
    }
}

// Phosphor Green Cyberpunk Colors
private val PhosphorGreen = Color(0xFF39FF14)
private val GunmetalBg = Color(0xFF0A0F0A)

/**
 * PiP-style Chat Screen - Transparent background with keyboard-aware height constraint
 * 
 * When keyboard opens, the PiP window stays anchored and squishes vertically.
 * Iris (header) and input field stay visible; response area shrinks via weight(1f).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChatScreenPiP(
    screenshot: Bitmap?,
    responseText: String,
    isGenerating: Boolean,
    isEngineReady: Boolean,
    onSendQuery: (String) -> Unit,
    onClose: () -> Unit
) {
    var isVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { isVisible = true }

    // Keyboard insets
    val imeInsets = WindowInsets.ime
    val density = LocalDensity.current
    val keyboardHeight = imeInsets.getBottom(density)
    val keyboardHeightDp = with(density) { keyboardHeight.toDp() }
    
    // Screen dimensions for height calculation
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val topMargin = 120.dp  // Increased for Pip-Boy wrist position
    val bottomMargin = 16.dp  // Tighter for compact display
    val safetyPadding = 8.dp
    
    // Calculate max available height: screen minus keyboard minus margins
    // Pip-Boy compact terminal: 380dp max
    val availableHeight = if (keyboardHeightDp > 0.dp) {
        screenHeight - keyboardHeightDp - topMargin - bottomMargin - safetyPadding
    } else {
        380.dp // Default compact height when keyboard closed
    }.coerceAtMost(380.dp).coerceAtLeast(180.dp) // Min to keep Iris visible

    // Detect if keyboard is open for GhostInterface
    val isKeyboardOpen = keyboardHeightDp > 0.dp

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
            .padding(end = 16.dp, top = topMargin, bottom = bottomMargin),
        contentAlignment = Alignment.TopEnd
    ) {
        AnimatedVisibility(
            visible = isVisible,
            enter = slideInHorizontally(
                initialOffsetX = { it },
                animationSpec = spring(stiffness = 300f, dampingRatio = 0.8f)
            ),
            exit = slideOutHorizontally(
                targetOffsetX = { it },
                animationSpec = tween(durationMillis = 300)
            )
        ) {
            // Pip-Boy terminal container: compact industrial housing
            Box(
                modifier = Modifier
                    .width(260.dp)  // Compact wrist-mounted display
                    .heightIn(max = availableHeight)
                    .clip(RoundedCornerShape(2.dp))  // Almost square industrial corners
                    .background(GunmetalBg.copy(alpha = 0.95f))
                    .border(
                        width = 4.dp,
                        color = PhosphorGreen.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(2.dp)
                    )
            ) {
                GhostInterface(
                    capturedBitmap = screenshot,
                    responseText = responseText,
                    isGenerating = isGenerating,
                    isEngineReady = isEngineReady,
                    isKeyboardOpen = isKeyboardOpen,
                    onSendQuery = onSendQuery,
                    onClose = onClose
                )
            }
        }
    }
}
