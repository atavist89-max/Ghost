package com.ghost.app

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import java.io.File
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
import com.ghost.app.inference.PiperTTS
import com.ghost.app.notification.NotificationPrefs
import com.ghost.app.notification.NotificationRepository
import com.ghost.app.ui.GhostInterface
import java.util.Locale
import java.text.SimpleDateFormat
import java.util.Date
import androidx.compose.ui.ExperimentalComposeUiApi
import com.ghost.app.ui.theme.GhostTheme
import com.ghost.app.utils.DebugLogger
import com.ghost.app.utils.MemoryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private var piperTTS: PiperTTS? = null
    private val mainScope = CoroutineScope(Dispatchers.Main)

    private var capturedBitmap: Bitmap? = null
    private val _responseText = mutableStateOf("")
    private val _isGenerating = mutableStateOf(false)
    private val _isEngineReady = mutableStateOf(false)
    private val _isVisualMode = mutableStateOf(false)
    private val _isNetEnabled = mutableStateOf(false)
    private val _isNotificationMode = mutableStateOf(false)
    private val _notificationHistory = mutableStateOf<String?>(null)
    private val _notificationCutoffLabel = mutableStateOf<String?>(null)
    private val _lastUserQuery = mutableStateOf("")
    private val _availableNotificationApps = mutableStateOf<List<String>>(emptyList())
    private val _excludedNotificationApps = mutableStateOf<Set<String>>(emptySet())
    private var notificationRepository: NotificationRepository? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate - PiP Activity mode")

        try {
            File("/storage/emulated/0/Download/GhostModels/debug_log.txt").delete()
            File("/storage/emulated/0/Download/GhostModels/wikipedia_debug.txt").delete()
        } catch (e: Exception) { }

        enableEdgeToEdge()

        val bytes = intent.getByteArrayExtra(EXTRA_SCREENSHOT_BYTES)
        if (bytes != null) {
            capturedBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            val msg = "Screenshot loaded: ${capturedBitmap?.width}x${capturedBitmap?.height}"
            Log.d(TAG, msg)
            DebugLogger.d(TAG, msg)
        } else {
            DebugLogger.e(TAG, "No screenshot bytes in intent!")
        }

        initializeEngine()

        notificationRepository = NotificationRepository(this)

        _excludedNotificationApps.value = NotificationPrefs.loadExcludedApps(this)
        mainScope.launch(Dispatchers.IO) {
            try {
                val apps = notificationRepository!!.getDistinctAppLabels()
                withContext(Dispatchers.Main) {
                    _availableNotificationApps.value = apps
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load app labels", e)
            }
        }

        mainScope.launch(Dispatchers.IO) {
            try {
                val deleted = notificationRepository!!.cleanupOldNotifications()
                Log.i(TAG, "PiP startup cleanup deleted $deleted old notifications")
            } catch (e: Exception) {
                Log.e(TAG, "Cleanup failed", e)
            }
        }

        mainScope.launch(Dispatchers.IO) {
            try {
                val deleted = notificationRepository!!.cleanupDuplicateNotifications()
                Log.i(TAG, "PiP startup dedup cleanup deleted $deleted duplicate notifications")
            } catch (e: Exception) {
                Log.e(TAG, "Deduplication cleanup failed", e)
            }
        }

        piperTTS = PiperTTS(this).apply {
            val initialized = initialize()
            Log.i(TAG, "Piper TTS initialized: $initialized, sampleRate=${getSampleRate()}")
        }

        setContent {
            GhostTheme {
                ChatScreenPiP(
                    screenshot = if (_isVisualMode.value) capturedBitmap else null,
                    responseText = _responseText.value,
                    isGenerating = _isGenerating.value,
                    isEngineReady = _isEngineReady.value,
                    tts = piperTTS,
                    isVisualMode = _isVisualMode.value,
                    onVisualModeChange = { _isVisualMode.value = it },
                    isNetEnabled = _isNetEnabled.value,
                    onNetToggle = { _isNetEnabled.value = it },
                    isNetConfigured = true,
                    isNotificationMode = _isNotificationMode.value,
                    onNotificationToggle = { enabled ->
                        if (enabled) {
                            _isNetEnabled.value = false
                            _responseText.value = ""
                            mainScope.launch(Dispatchers.IO) {
                                try {
                                    val excluded = _excludedNotificationApps.value.toList()
                                    val totalCount = notificationRepository!!.getTotalCount(excluded)
                                    if (totalCount == 0) {
                                        withContext(Dispatchers.Main) {
                                            _notificationCutoffLabel.value = "🔔 0/0 · no matches"
                                            _isNotificationMode.value = true
                                        }
                                    } else {
                                        val result = notificationRepository!!.runNotificationPipeline(
                                            userQuery = "",
                                            excludedApps = excluded,
                                            appLabels = notificationRepository!!.getDistinctAppLabels(),
                                            quickInfer = { prompt -> inferenceEngine!!.quickInfer(prompt) },
                                            onEscalation = { msg ->
                                                mainScope.launch(Dispatchers.Main) {
                                                    Toast.makeText(this@ChatActivity, msg, Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        )
                                        val historyText = notificationRepository!!.buildHistoryText(result.analyzedEntries)
                                        val label = buildNotificationLabel(
                                            result.analyzedCount,
                                            result.totalMatches,
                                            result.oldestIncludedTimestamp,
                                            result.isEntryTooLarge
                                        )
                                        withContext(Dispatchers.Main) {
                                            _notificationHistory.value = historyText
                                            _notificationCutoffLabel.value = label
                                            _isNotificationMode.value = true
                                        }
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        _responseText.value = "Error loading notifications: ${e.message}"
                                    }
                                }
                            }
                        } else {
                            _isNotificationMode.value = false
                            _notificationHistory.value = null
                            _notificationCutoffLabel.value = null
                        }
                    },

                    notificationCutoffLabel = _notificationCutoffLabel.value,
                    availableNotificationApps = _availableNotificationApps.value,
                    excludedNotificationApps = _excludedNotificationApps.value,
                    onNotificationAppSelectionChange = { excluded ->
                        _excludedNotificationApps.value = excluded
                        NotificationPrefs.saveExcludedApps(this, excluded)
                        if (_isNotificationMode.value) {
                            mainScope.launch(Dispatchers.IO) {
                                try {
                                    val excludedList = excluded.toList()
                                    val totalCount = notificationRepository!!.getTotalCount(excludedList)
                                    if (totalCount == 0) {
                                        withContext(Dispatchers.Main) {
                                            _notificationCutoffLabel.value = "🔔 0/0 · no matches"
                                            _notificationHistory.value = ""
                                        }
                                    } else {
                                        val result = notificationRepository!!.runNotificationPipeline(
                                            userQuery = "",
                                            excludedApps = excludedList,
                                            appLabels = notificationRepository!!.getDistinctAppLabels(),
                                            quickInfer = { prompt -> inferenceEngine!!.quickInfer(prompt) },
                                            onEscalation = { msg ->
                                                mainScope.launch(Dispatchers.Main) {
                                                    Toast.makeText(this@ChatActivity, msg, Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        )
                                        val historyText = notificationRepository!!.buildHistoryText(result.analyzedEntries)
                                        val label = buildNotificationLabel(
                                            result.analyzedCount,
                                            result.totalMatches,
                                            result.oldestIncludedTimestamp,
                                            result.isEntryTooLarge
                                        )
                                        withContext(Dispatchers.Main) {
                                            _notificationHistory.value = historyText
                                            _notificationCutoffLabel.value = label
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to refresh on app filter change", e)
                                }
                            }
                        }
                    },
                    userQuery = _lastUserQuery.value,
                    onSendQuery = { query ->
                        if (_isNetEnabled.value && _responseText.value.contains("WEB SEARCH ERROR")) {
                            _responseText.value = "WEB SEARCH ERROR: Previous Wikipedia search failed.\n\nTurn OFF web toggle to use local LLM."
                        } else {
                            _lastUserQuery.value = query
                            handleQuery(
                                query = query,
                                useVisualMode = _isVisualMode.value,
                                useNetSearch = _isNetEnabled.value
                            )
                        }
                    },
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

    private fun buildNotificationLabel(
        analyzedCount: Int,
        totalMatches: Int,
        oldestTimestamp: Long?,
        isEntryTooLarge: Boolean
    ): String {
        return when {
            isEntryTooLarge -> "🔔 0/$totalMatches · entry exceeds budget"
            totalMatches == 0 -> "🔔 0/0 · no matches"
            analyzedCount == totalMatches -> "🔔 $analyzedCount/$totalMatches · full set"
            else -> {
                val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                val dateStr = oldestTimestamp?.let { sdf.format(Date(it)) } ?: "unknown"
                "🔔 $analyzedCount/$totalMatches · back to $dateStr"
            }
        }
    }

    private fun handleQuery(query: String, useVisualMode: Boolean, useNetSearch: Boolean) {
        Log.i(TAG, "User query: $query (visualMode=$useVisualMode, netSearch=$useNetSearch)")
        DebugLogger.i(TAG, "User query: $query (visualMode=$useVisualMode, netSearch=$useNetSearch)")

        if (useVisualMode && capturedBitmap == null) {
            _responseText.value = "Error: No screenshot available"
            DebugLogger.e(TAG, "No screenshot available!")
            return
        }

        val bitmap = if (useVisualMode) capturedBitmap else null
        if (bitmap != null && bitmap.isRecycled) {
            Log.e(TAG, "Bitmap is recycled!")
            DebugLogger.e(TAG, "Bitmap is recycled!")
            _responseText.value = "Error: Screenshot was recycled"
            return
        }

        if (bitmap != null) {
            val bitmapInfo = "Bitmap: ${bitmap.width}x${bitmap.height}, config=${bitmap.config}, " +
                    "byteCount=${bitmap.byteCount / 1024}KB"
            Log.i(TAG, "Sending bitmap to inference: $bitmapInfo")
            DebugLogger.i(TAG, bitmapInfo)
        }

        if (_isNotificationMode.value) {
            _responseText.value = "> ANALYZING QUERY..."
            _isGenerating.value = true
            mainScope.launch(Dispatchers.IO) {
                try {
                    val excluded = _excludedNotificationApps.value.toList()
                    val result = notificationRepository!!.runNotificationPipeline(
                        userQuery = query,
                        excludedApps = excluded,
                        appLabels = notificationRepository!!.getDistinctAppLabels(),
                        quickInfer = { prompt -> inferenceEngine!!.quickInfer(prompt) },
                        onEscalation = { msg ->
                            mainScope.launch(Dispatchers.Main) {
                                Toast.makeText(this@ChatActivity, msg, Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                    val historyText = notificationRepository!!.buildHistoryText(result.analyzedEntries)
                    val label = buildNotificationLabel(
                        result.analyzedCount,
                        result.totalMatches,
                        result.oldestIncludedTimestamp,
                        result.isEntryTooLarge
                    )
                    withContext(Dispatchers.Main) {
                        _notificationHistory.value = historyText
                        _notificationCutoffLabel.value = label
                    }

                    if (result.isEntryTooLarge) {
                        withContext(Dispatchers.Main) {
                            _responseText.value = "One matching notification was found but exceeds the analysis budget. Try a more specific query."
                            _isGenerating.value = false
                        }
                        return@launch
                    }

                    val filterMeta = buildString {
                        append("\n\n[Search metadata: confidence=${result.structuredFilter.confidence}, strategy=${result.structuredFilter.strategy}")
                        if (result.escalationStep > 0) {
                            append(", escalation=${result.escalationStep}")
                        }
                        if (result.structuredFilter.targetApps.isNotEmpty()) {
                            append(", apps=${result.structuredFilter.targetApps.joinToString(", ")}")
                        }
                        append("]")
                    }
                    val historyWithMeta = historyText + filterMeta

                    inferenceEngine?.analyze(
                        bitmap = bitmap,
                        query = query,
                        useVisualMode = useVisualMode,
                        useWebSearch = useNetSearch,
                        useNotificationHistory = true,
                        notificationHistory = historyWithMeta,
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
                                if (_isNetEnabled.value) {
                                    _responseText.value = "WEB SEARCH ERROR:\n$error\n\n[Toggle web OFF for local LLM]"
                                } else {
                                    _responseText.value += "\nError: $error"
                                }
                                _isGenerating.value = false
                                DebugLogger.e(TAG, "Inference error: $error")
                            }
                        },
                        onWebSearchError = { error ->
                            mainScope.launch {
                                _responseText.value = "WEB SEARCH ERROR:\n$error\n\n[Toggle web OFF for local LLM]"
                                Toast.makeText(this@ChatActivity, error, Toast.LENGTH_LONG).show()
                            }
                        }
                    )
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        _responseText.value = "Error: ${e.message}"
                        _isGenerating.value = false
                    }
                }
            }
        } else {
            _responseText.value = ""
            _isGenerating.value = true
            inferenceEngine?.analyze(
                bitmap = bitmap,
                query = query,
                useVisualMode = useVisualMode,
                useWebSearch = useNetSearch,
                useNotificationHistory = false,
                notificationHistory = null,
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
                        if (_isNetEnabled.value) {
                            _responseText.value = "WEB SEARCH ERROR:\n$error\n\n[Toggle web OFF for local LLM]"
                        } else {
                            _responseText.value += "\nError: $error"
                        }
                        _isGenerating.value = false
                        DebugLogger.e(TAG, "Inference error: $error")
                    }
                },
                onWebSearchError = { error ->
                    mainScope.launch {
                        _responseText.value = "WEB SEARCH ERROR:
$error

[Toggle web OFF for local LLM]"
                        Toast.makeText(this@ChatActivity, error, Toast.LENGTH_LONG).show()
                    }
                }
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")

        piperTTS?.release()
        piperTTS = null

        inferenceEngine?.close()
        inferenceEngine = null

        notificationRepository?.close()
        notificationRepository = null

        capturedBitmap?.recycle()
        capturedBitmap = null

        MemoryManager.releaseAll()
    }

    override fun onBackPressed() {
        finishAndRemoveTask()
    }
}

private val PhosphorGreen = Color(0xFF39FF14)
private val GunmetalBg = Color(0xFF0A0F0A)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChatScreenPiP(
    screenshot: Bitmap?,
    responseText: String,
    isGenerating: Boolean,
    isEngineReady: Boolean,
    tts: PiperTTS?,
    isVisualMode: Boolean,
    onVisualModeChange: (Boolean) -> Unit,
    isNetEnabled: Boolean,
    onNetToggle: (Boolean) -> Unit,
    isNetConfigured: Boolean = true,
    isNotificationMode: Boolean = false,
    onNotificationToggle: (Boolean) -> Unit = {},
    notificationCutoffLabel: String? = null,
    availableNotificationApps: List<String> = emptyList(),
    excludedNotificationApps: Set<String> = emptySet(),
    onNotificationAppSelectionChange: (Set<String>) -> Unit = {},
    userQuery: String? = null,
    onSendQuery: (String) -> Unit,
    onClose: () -> Unit
) {
    var isVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { isVisible = true }

    val imeInsets = WindowInsets.ime
    val density = LocalDensity.current
    val keyboardHeight = imeInsets.getBottom(density)
    val keyboardHeightDp = with(density) { keyboardHeight.toDp() }

    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val topMargin = 120.dp
    val bottomMargin = 16.dp
    val safetyPadding = 8.dp

    val availableHeight = if (keyboardHeightDp > 0.dp) {
        screenHeight - keyboardHeightDp - topMargin - bottomMargin - safetyPadding
    } else {
        380.dp
    }.coerceAtMost(380.dp).coerceAtLeast(180.dp)

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
            Box(
                modifier = Modifier
                    .width(260.dp)
                    .heightIn(max = availableHeight)
                    .clip(RoundedCornerShape(2.dp))
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
                    tts = tts,
                    isVisualMode = isVisualMode,
                    onVisualModeChange = onVisualModeChange,
                    isNetEnabled = isNetEnabled,
                    onNetToggle = onNetToggle,
                    isNetConfigured = isNetConfigured,
                    isNotificationMode = isNotificationMode,
                    onNotificationToggle = onNotificationToggle,
                    notificationCutoffLabel = notificationCutoffLabel,
                    availableNotificationApps = availableNotificationApps,
                    excludedNotificationApps = excludedNotificationApps,
                    onNotificationAppSelectionChange = onNotificationAppSelectionChange,
                    userQuery = userQuery,
                    onSendQuery = onSendQuery,
                    onClose = onClose
                )
            }
        }
    }
}
