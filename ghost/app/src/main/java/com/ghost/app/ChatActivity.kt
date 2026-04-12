package com.ghost.app

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghost.app.inference.InferenceEngine
import com.ghost.app.ui.theme.GhostTheme
import com.ghost.app.utils.DebugLogger
import com.ghost.app.utils.MemoryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Full-screen chat activity for interacting with the LLM.
 * Displays the captured screenshot and chat interface.
 * 
 * DEBUG FEATURE: Includes in-app debug panel for viewing logs without ADB.
 */
class ChatActivity : ComponentActivity() {

    companion object {
        private const val TAG = "ChatActivity"
        const val EXTRA_SCREENSHOT_BYTES = "screenshot_bytes"
        
        fun createIntent(activity: Activity, bitmap: Bitmap): Intent {
            val intent = Intent(activity, ChatActivity::class.java)
            // Convert bitmap to bytes for passing via intent
            val stream = java.io.ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
            intent.putExtra(EXTRA_SCREENSHOT_BYTES, stream.toByteArray())
            return intent
        }
    }

    private var inferenceEngine: InferenceEngine? = null
    private val mainScope = CoroutineScope(Dispatchers.Main)
    
    private var capturedBitmap: Bitmap? = null
    private var _responseText = mutableStateOf("")
    private var _isGenerating = mutableStateOf(false)
    private var _isEngineReady = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate")
        
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
        
        // Set up Compose UI
        setContent {
            GhostTheme {
                ChatScreenWithDebug(
                    screenshot = capturedBitmap,
                    responseText = _responseText.value,
                    isGenerating = _isGenerating.value,
                    isEngineReady = _isEngineReady.value,
                    onSendQuery = { query -> handleQuery(query) },
                    onClose = { finish() }
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
        
        // Verify bitmap is valid before sending to inference
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreenWithDebug(
    screenshot: Bitmap?,
    responseText: String,
    isGenerating: Boolean,
    isEngineReady: Boolean,
    onSendQuery: (String) -> Unit,
    onClose: () -> Unit
) {
    var showDebugPanel by remember { mutableStateOf(false) }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ghost Assistant") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                actions = {
                    // Debug button
                    TextButton(
                        onClick = { showDebugPanel = true },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text("Debug", fontSize = 12.sp)
                    }
                    TextButton(onClick = onClose) {
                        Text("Close")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Main chat UI
            ChatScreenContent(
                screenshot = screenshot,
                responseText = responseText,
                isGenerating = isGenerating,
                isEngineReady = isEngineReady,
                onSendQuery = onSendQuery,
                modifier = Modifier.fillMaxSize()
            )
            
            // Debug panel overlay
            if (showDebugPanel) {
                DebugPanel(
                    onDismiss = { showDebugPanel = false },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@Composable
fun ChatScreenContent(
    screenshot: Bitmap?,
    responseText: String,
    isGenerating: Boolean,
    isEngineReady: Boolean,
    onSendQuery: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var queryText by remember { mutableStateOf("") }
    val scrollState = rememberScrollState()
    
    Column(
        modifier = modifier
            .padding(16.dp)
    ) {
        // Screenshot thumbnail
        if (screenshot != null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Image(
                    bitmap = screenshot.asImageBitmap(),
                    contentDescription = "Captured screenshot",
                    modifier = Modifier.fillMaxSize()
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
        }
        
        // Status indicator
        if (!isEngineReady) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("Loading model...")
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
        
        // Response area
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                if (responseText.isEmpty() && !isGenerating) {
                    Text(
                        "Ask me anything about your screen...",
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                    ) {
                        Text(
                            text = responseText,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        if (isGenerating) {
                            Spacer(modifier = Modifier.height(8.dp))
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        }
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Input area
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicTextField(
                value = queryText,
                onValueChange = { queryText = it },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                textStyle = TextStyle(fontSize = 16.sp),
                enabled = isEngineReady && !isGenerating,
                decorationBox = { innerTextField ->
                    Box {
                        if (queryText.isEmpty()) {
                            Text(
                                "Ask about your screen...",
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                fontSize = 16.sp
                            )
                        }
                        innerTextField()
                    }
                }
            )
            
            Spacer(modifier = Modifier.width(8.dp))
            
            Button(
                onClick = {
                    if (queryText.isNotBlank()) {
                        onSendQuery(queryText)
                        queryText = ""
                    }
                },
                enabled = isEngineReady && !isGenerating && queryText.isNotBlank(),
                shape = RoundedCornerShape(24.dp)
            ) {
                Text("Send")
            }
        }
    }
}

@Composable
fun DebugPanel(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var logs by remember { mutableStateOf(DebugLogger.getLogsText()) }
    val scrollState = rememberScrollState()
    
    // Update logs when they change
    DisposableEffect(Unit) {
        val listener = { 
            logs = DebugLogger.getLogsText()
        }
        DebugLogger.addListener(listener)
        onDispose {
            DebugLogger.removeListener(listener)
        }
    }
    
    // Auto-scroll to bottom when logs update
    LaunchedEffect(logs) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }
    
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Debug Logs",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
                Row {
                    TextButton(
                        onClick = { DebugLogger.clear() }
                    ) {
                        Text("Clear")
                    }
                    TextButton(onClick = onDismiss) {
                        Text("Close")
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Log display
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp)
                ) {
                    Text(
                        text = logs.ifEmpty { "No logs yet..." },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Quick info
            Text(
                "Showing last ${DebugLogger.getLogs().size} log entries",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
