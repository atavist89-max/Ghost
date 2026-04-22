package com.ghost.app.inference

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.ghost.app.notification.KeywordExtractor
import com.ghost.app.notification.NotificationRepository
import com.ghost.app.utils.DebugLogger
import com.ghost.app.utils.GhostPaths
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Inference engine for local LLM using Google's official LiteRT-LM library.
 * Manages model loading and text generation with vision input.
 * 
 * Uses .litertlm format models (Gemma, Llama, Phi-4, Qwen supported).
 * 
 * Vision Model Support:
 * - Gemma 3n/4 vision models require special prompt template with <image_soft_token>
 * - Image must be saved to disk and path passed via prompt template
 * - GPU backend required for vision processing
 */
class InferenceEngine(private val context: Context) {

    companion object {
        private const val TAG = "GhostInference"

        // Generation parameters
        private const val MAX_TOKENS = 512
        private const val TEMPERATURE = 0.7
        private const val TOP_K = 40
        private const val TOP_P = 0.9
        
        // Screenshot cache filename
        private const val SCREENSHOT_CACHE_FILE = "ghost_screenshot.jpg"
    }

    private fun logToFile(tag: String, message: String) {
        try {
            val logFile = File("/storage/emulated/0/Download/GhostModels/debug_log.txt")
            val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            logFile.appendText("[$timestamp] [$tag] $message\n")
        } catch (e: Exception) {
            // Silent fail
        }
    }

    private var engine: Engine? = null
    private val thermalMonitor = ThermalMonitor(context)
    private val isInitialized = AtomicBoolean(false)
    private val isClosed = AtomicBoolean(false)

    private val mainScope = CoroutineScope(Dispatchers.Main)
    private val inferenceScope = CoroutineScope(Dispatchers.Default)

    private val wikipediaService = WikipediaSearchService(context)
    private val notificationRepository = NotificationRepository(context)

    /**
     * Initialize the inference engine with the model.
     * For vision models, GPU backend is required for image processing.
     *
     * @param onComplete Callback with success/failure status
     */
    fun initialize(onComplete: (Boolean, String?) -> Unit) {
        if (isInitialized.get()) {
            onComplete(true, null)
            return
        }

        inferenceScope.launch {
            try {
                // Try to find the model file
                val modelFile = GhostPaths.findModelFile() 
                    ?: File(GhostPaths.MODEL_PATH)

                // Log model file info for debugging
                val modelInfo = "Loading model from: ${modelFile.absolutePath}"
                Log.i(TAG, modelInfo)
                DebugLogger.i(TAG, modelInfo)
                
                val existsMsg = "Model exists: ${modelFile.exists()}"
                Log.i(TAG, existsMsg)
                DebugLogger.i(TAG, existsMsg)
                
                val sizeBytes = modelFile.length()
                val sizeMsg = "Model size: $sizeBytes bytes"
                Log.i(TAG, sizeMsg)
                DebugLogger.i(TAG, sizeMsg)
                
                // Vision models are typically 2GB+, text-only are ~1.5GB
                val modelSizeMB = sizeBytes / (1024 * 1024)
                val sizeMBMsg = "Model size: ${modelSizeMB}MB"
                Log.i(TAG, sizeMBMsg)
                DebugLogger.i(TAG, sizeMBMsg)
                
                if (modelSizeMB < 2000) {
                    val warning = "WARNING: Model is <2GB, may be text-only variant. Vision models are typically 2.5GB+"
                    Log.w(TAG, warning)
                    DebugLogger.w(TAG, warning)
                } else {
                    val okMsg = "Model appears to be vision-enabled (${modelSizeMB}MB)"
                    Log.i(TAG, okMsg)
                    DebugLogger.i(TAG, okMsg)
                }

                if (!modelFile.exists()) {
                    throw IllegalStateException(
                        "Model file not found at ${modelFile.absolutePath}. " +
                        "Please place your .litertlm model in ${GhostPaths.getDisplayPath()}"
                    )
                }

                // Check thermal status to decide backend
                thermalMonitor.checkThermalStatus()
                
                // For vision models, GPU is required for image processing
                val backend = if (thermalMonitor.shouldUseGpu()) {
                        val backendMsg = "Using GPU backend for vision processing"
                    Log.i(TAG, backendMsg)
                    DebugLogger.i(TAG, backendMsg)
                    Backend.GPU()
                } else {
                    val backendMsg = "Using CPU backend (GPU recommended for vision)"
                    Log.i(TAG, backendMsg)
                    DebugLogger.i(TAG, backendMsg)
                    Backend.CPU()
                }

                // Create engine configuration
                val engineConfig = EngineConfig(
                    modelPath = modelFile.absolutePath,
                    backend = backend,
                    cacheDir = context.cacheDir.path
                )

                // Create and initialize engine
                engine = Engine(engineConfig)
                engine?.initialize()

                isInitialized.set(true)
                    val initMsg = "Inference engine initialized successfully"
                    Log.i(TAG, initMsg)
                    DebugLogger.i(TAG, initMsg)

                withContext(Dispatchers.Main) {
                    onComplete(true, null)
                }

            } catch (e: Exception) {
                val errorMsg = "Failed to initialize inference engine: ${e.message}"
                Log.e(TAG, errorMsg, e)
                DebugLogger.e(TAG, errorMsg, e)
                withContext(Dispatchers.Main) {
                    onComplete(false, e.message)
                }
            }
        }
    }

    /**
     * Analyze a query using the inference engine.
     *
     * @param bitmap The screenshot to analyze (only used in visual mode)
     * @param query User's question
     * @param useVisualMode Whether to use visual mode with screenshot
     * @param useWebSearch Whether to fetch Wikipedia search results as context
     * @param onToken Callback for each token as it's generated
     * @param onComplete Callback when generation is complete
     * @param onError Callback if an error occurs
     * @param onWebCreditsUpdate Callback when Wikipedia search completes (unused, kept for compatibility)
     */
    fun analyze(
        bitmap: Bitmap?,
        query: String,
        useVisualMode: Boolean = false,
        useWebSearch: Boolean = false,
        useNotificationHistory: Boolean = false,
        notificationHistory: String? = null,
        onToken: (String) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit,
        onWebCreditsUpdate: ((Int) -> Unit)? = null,
        onWebSearchError: ((String) -> Unit)? = null
    ) {
        if (!isInitialized.get() || isClosed.get()) {
            onError("Inference engine not initialized")
            return
        }

        inferenceScope.launch {
            try {
                var articleTitle: String? = null
                var articleContent: String? = null

                if (useWebSearch) {
                    try {
                        Log.i(TAG, "Fetching Wikipedia article for query: $query")
                        val (title, content) = wikipediaService.searchAndExtract(query)
                        articleTitle = title
                        articleContent = content
                        onWebCreditsUpdate?.invoke(-1) // No credit tracking for Wikipedia
                        val estimatedArticleTokens = content.length / 4
                        Log.i(TAG, "Wikipedia article ready: '$title' (${content.length} chars, ~$estimatedArticleTokens tokens)")
                    } catch (e: Exception) {
                        Log.e(TAG, "Wikipedia search failed: ${e.message}", e)
                        val errorMsg = "Failed to fetch Wikipedia article: ${e.message ?: "Unknown error"}"
                        logToFile("ENGINE_ERROR", errorMsg)
                        withContext(Dispatchers.Main) {
                            onWebSearchError?.invoke(errorMsg)
                            onError(errorMsg)
                            onComplete()
                        }
                        return@launch // HARD STOP - no local fallback
                    }
                }

                val engineInstance = engine ?: throw IllegalStateException("Engine is null")
                val conversation = engineInstance.createConversation()

                try {
                    val basePersona = if (useVisualMode && bitmap != null) {
                        "You are a robotic visual analysis assistant. Provide brief, factual, and logically structured responses devoid of emotion or elaboration. Analyze the screenshot and report only relevant technical findings with machine-like precision. CRITICAL: Use only plain text with no formatting. Do not use asterisks, stars, bullet points, markdown, or any special characters for emphasis. Write as if outputting to a 1970s monochrome terminal."
                    } else {
                        "You are a robotic computer assistant. Provide brief, factual, and logically structured responses devoid of emotion, conversational filler, or elaboration. Respond with machine-like precision and efficiency. CRITICAL: Use only plain text with no formatting. Do not use asterisks, stars, bullet points, markdown, or any special characters for emphasis. Write as if outputting to a 1970s monochrome terminal."
                    }

                    var filterCutoffDate: java.time.LocalDate? = null

                    val finalPrompt = when {
                        useNotificationHistory -> {
                            val keywords = KeywordExtractor.extract(query)
                            val matched = withContext(Dispatchers.IO) {
                                notificationRepository.searchByKeywords(keywords)
                            }
                            if (matched.isEmpty()) {
                                mainScope.launch {
                                    onToken("No matching notifications found in your history.")
                                    onComplete()
                                }
                                return@launch
                            }
                            val filterResult = notificationRepository.applyDayBoundaryFilter(matched)
                            val historyText = notificationRepository.buildHistoryText(filterResult.analyzedEntries)
                            if (historyText.isEmpty()) {
                                mainScope.launch {
                                    onToken("No matching notifications found in your history.")
                                    onComplete()
                                }
                                return@launch
                            }
                            filterCutoffDate = filterResult.cutoffDate
                            buildString {
                                append("You are Ghost. Answer based ONLY on the provided notification history below.")
                                append("\n\nNotifications:\n")
                                append(historyText)
                                append("\n\nUser question: ")
                                append(query)
                                append("\n\nCRITICAL: Use only plain text with no formatting. Do not use asterisks, stars, bullet points, markdown, or any special characters for emphasis. Write as if outputting to a 1970s monochrome terminal.")
                            }
                        }
                        articleTitle != null && articleContent != null -> {
                            buildString {
                                append(basePersona)
                                append("\nWikipedia Article Title: ")
                                append(articleTitle)
                                append("\nWikipedia Article Content: ")
                                append(articleContent)
                                append("\n\nuser_query: ")
                                append(query)
                                append("\nCRITICAL INSTRUCTION: Answer the user_query using ONLY the Wikipedia Article Content provided above. If article was truncated, focus on key facts in the provided content. Start your response with 'Based on Wiki-page ")
                                append(articleTitle)
                                append(":' followed by exactly 5 concise sentences. Do not include any other text.")
                            }
                        }
                        else -> {
                            "$basePersona USER QUERY: $query"
                        }
                    }

                    val estimatedTotalTokens = finalPrompt.length / 4
                    Log.i(TAG, "FINAL PROMPT LENGTH: ${finalPrompt.length} chars, ~$estimatedTotalTokens tokens")
                    logToFile("ENGINE", "Article: ~${articleContent?.length?.div(4) ?: 0} tokens (${articleContent?.length ?: 0} chars)")
                    logToFile("ENGINE", "Total prompt: ~$estimatedTotalTokens tokens (${finalPrompt.length} chars)")
                    if (estimatedTotalTokens > 3500) {
                        logToFile("ENGINE_WARNING", "Prompt approaching 4K limit! May fail.")
                    }

                    val userMessageObj = if (useVisualMode && bitmap != null) {
                        val imagePath = saveBitmapToCache(bitmap)
                        if (imagePath != null) {
                            Message.of(finalPrompt)
                        } else {
                            Message.of("$finalPrompt (screenshot failed)")
                        }
                    } else {
                        Message.of(finalPrompt)
                    }

                    val sendingMsg = "Message object created, sending to model..."
                    Log.d(TAG, sendingMsg)
                    DebugLogger.d(TAG, sendingMsg)

                    val responseMessage = conversation.sendMessage(userMessageObj)
                    val responseText = responseMessage.toString()

                    val responseMsg = "Response received: ${responseText.take(100)}..."
                    Log.i(TAG, responseMsg)
                    DebugLogger.i(TAG, responseMsg)

                    // Simulate streaming by splitting into words/tokens
                    val tokens = responseText.split(" ")

                    for (token in tokens) {
                        mainScope.launch {
                            onToken("$token ")
                        }
                        kotlinx.coroutines.delay(30)
                    }

                    withContext(Dispatchers.Main) {
                        onComplete()
                    }

                    // Post-query destructive delete for notification mode
                    if (useNotificationHistory && filterCutoffDate != null) {
                        val cutoffMillis = filterCutoffDate.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                        val deleted = notificationRepository.deleteOlderThan(cutoffMillis)
                        Log.i(TAG, "Post-query delete: $deleted rows older than $cutoffMillis")
                    }

                    if (useWebSearch) {
                        return@launch // HARD STOP SUCCESS
                    }
                } finally {
                    conversation.close()
                    kotlinx.coroutines.delay(100)
                    if (useVisualMode) cleanupScreenshotCache()
                }

            } catch (e: Exception) {
                val errorMsg = "Inference failed: ${e.message}"
                Log.e(TAG, errorMsg, e)
                DebugLogger.e(TAG, errorMsg, e)
                withContext(Dispatchers.Main) {
                    onError(e.message ?: "Inference failed")
                }
            }
        }
    }

    /**
     * Build vision prompt with image token for Gemma 3n/4 models.
     * 
     * Based on LiteRT-LM GitHub issue #1874 and Gemma3 template:
     * https://github.com/google-ai-edge/LiteRT-LM/issues/1874
     * 
     * The prompt template must include <image_soft_token> or similar
     * for the model to recognize image input.
     * 
     * Gemma3 template format:
     * {%- for item in message['content'] -%}
     *   {%- if item['type'] == 'image' -%}
     *     {{ '<image_soft_token>' }}
     *   {%- elif item['type'] == 'text' -%}
     *     {{ item['text'] | trim }}
     *   {%- endif -%}
     * {%- endfor -%}
     *
     * @param query User's question
     * @param imagePath Absolute path to saved screenshot
     * @return Formatted vision prompt
     */
    private fun buildVisionPrompt(query: String, imagePath: String): String {
        // Try multiple image token formats that vision models might recognize
        // Order: most likely to least likely
        
        // Persona prompt for robotic computer-style responses
        val personaPrompt = "You are a robotic visual analysis assistant. Provide brief, factual, and logically structured responses devoid of emotion, conversational filler, or elaboration. Analyze the screenshot and report only relevant findings with machine-like precision and efficiency. CRITICAL: Use only plain text with no formatting. Do not use asterisks, stars, bullet points, markdown, or any special characters for emphasis. Write as if outputting to a 1970s monochrome terminal."
        
        // Option 1: Gemma3/4 format with <image_soft_token> and system persona
        // This is the official token from the Gemma3 template
        return """<start_of_turn>system
$personaPrompt
<end_of_turn>
<start_of_turn>user
<image_soft_token>
$query
<end_of_turn>
<start_of_turn>model""".trimIndent()
        
        // Alternative formats if the above doesn't work:
        // Option 2: With image path embedded
        // return "<start_of_turn>user\n<|image|>\n$query\nImage: $imagePath\n<end_of_turn>\n<start_of_turn>model"
        
        // Option 3: JSON format (if Message.of() parses it)
        // return """{"role": "user", "content": [{"type": "image", "path": "$imagePath"}, {"type": "text", "text": "$query"}]}"""
    }

    /**
     * Save bitmap to cache directory for vision model input.
     * The image file must persist until inference completes.
     *
     * @param bitmap The screenshot bitmap to save
     * @return Absolute path to saved file, or null if save failed
     */
    private fun saveBitmapToCache(bitmap: Bitmap): String? {
        return try {
            // Ensure bitmap is ARGB_8888 format
            val safeBitmap = if (bitmap.config != Bitmap.Config.ARGB_8888) {
                Log.w(TAG, "Converting bitmap from ${bitmap.config} to ARGB_8888")
                bitmap.copy(Bitmap.Config.ARGB_8888, true)
            } else {
                bitmap
            }
            
            val cacheFile = File(context.cacheDir, SCREENSHOT_CACHE_FILE)
            FileOutputStream(cacheFile).use { out ->
                // Use JPEG 95% quality for best vision model performance
                safeBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                out.flush()
            }
            
            // Verify the file was written
            if (!cacheFile.exists() || cacheFile.length() == 0L) {
                Log.e(TAG, "Failed to write image file")
                return null
            }
            
            Log.i(TAG, "Bitmap saved: ${cacheFile.absolutePath} (${cacheFile.length()} bytes, ${safeBitmap.width}x${safeBitmap.height})")
            cacheFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Error saving bitmap to cache", e)
            null
        }
    }

    /**
     * Clean up cached screenshot file.
     */
    private fun cleanupScreenshotCache() {
        try {
            val cacheFile = File(context.cacheDir, SCREENSHOT_CACHE_FILE)
            if (cacheFile.exists()) {
                val deleted = cacheFile.delete()
                Log.d(TAG, "Cached screenshot cleaned up: $deleted")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error cleaning up screenshot cache", e)
        }
    }

    /**
     * Quick single-prompt inference for pipeline stages.
     * Does NOT use streaming. Caller must handle errors.
     */
    fun quickInfer(prompt: String): String {
        val engineInstance = engine ?: throw IllegalStateException("Engine not initialized")
        val conversation = engineInstance.createConversation()
        return try {
            val response = conversation.sendMessage(Message.of(prompt))
            response.toString().trim()
        } finally {
            conversation.close()
        }
    }

    /**
     * Close the inference engine and release all resources.
     */
    fun close() {
        if (isClosed.getAndSet(true)) {
            return
        }

        Log.i(TAG, "Closing inference engine")

        try {
            cleanupScreenshotCache()
            engine?.close()
            engine = null
            isInitialized.set(false)
            notificationRepository.close()
            Log.i(TAG, "Inference engine released")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing inference engine", e)
        }
    }

    fun isReady(): Boolean = isInitialized.get() && !isClosed.get()
    
    fun getThermalState(): ThermalMonitor.ThermalState = thermalMonitor.thermalState.value

    protected fun finalize() {
        close()
    }
}
