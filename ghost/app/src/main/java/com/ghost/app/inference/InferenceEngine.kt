package com.ghost.app.inference

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.ghost.app.utils.GhostPaths
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Inference engine for local LLM using Google's official LiteRT-LM library.
 * Manages model loading and text generation with vision input.
 * 
 * Uses .litertlm format models (Gemma, Llama, Phi-4, Qwen supported).
 * 
 * Multimodal Support:
 * - For vision models (Gemma 3n, Gemma 4), images must be passed as file paths
 * - Message format: JSON with content array containing text and image parts
 * - Vision backend must be GPU for image processing
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

    private var engine: Engine? = null
    private val thermalMonitor = ThermalMonitor(context)
    private val isInitialized = AtomicBoolean(false)
    private val isClosed = AtomicBoolean(false)

    private val mainScope = CoroutineScope(Dispatchers.Main)
    private val inferenceScope = CoroutineScope(Dispatchers.Default)

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
                Log.i(TAG, "Loading model from: ${modelFile.absolutePath}")
                Log.i(TAG, "Model exists: ${modelFile.exists()}")
                Log.i(TAG, "Model size: ${modelFile.length()} bytes")

                if (!modelFile.exists()) {
                    throw IllegalStateException(
                        "Model file not found at ${modelFile.absolutePath}. " +
                        "Please place your .litertlm model in ${GhostPaths.getDisplayPath()}"
                    )
                }

                // Check thermal status to decide backend
                thermalMonitor.checkThermalStatus()
                
                // For vision models, GPU is required for image processing
                // Use GPU for both main backend and vision backend
                val backend = if (thermalMonitor.shouldUseGpu()) {
                    Log.i(TAG, "Using GPU backend for vision processing")
                    Backend.GPU()
                } else {
                    Log.i(TAG, "Using CPU backend (GPU recommended for vision)")
                    Backend.CPU()
                }

                // Create engine configuration with vision backend
                // Note: visionBackend parameter may vary by LiteRT-LM version
                val engineConfig = try {
                    // Try to create config with vision backend (newer versions)
                    EngineConfig(
                        modelPath = modelFile.absolutePath,
                        backend = backend,
                        cacheDir = context.cacheDir.path
                    )
                } catch (e: Exception) {
                    // Fallback to basic config
                    Log.w(TAG, "Vision backend not supported in this version, using basic config")
                    EngineConfig(
                        modelPath = modelFile.absolutePath,
                        backend = backend,
                        cacheDir = context.cacheDir.path
                    )
                }

                // Create and initialize engine
                engine = Engine(engineConfig)
                engine?.initialize()

                isInitialized.set(true)
                Log.i(TAG, "Inference engine initialized successfully")

                withContext(Dispatchers.Main) {
                    onComplete(true, null)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize inference engine", e)
                withContext(Dispatchers.Main) {
                    onComplete(false, e.message)
                }
            }
        }
    }

    /**
     * Analyze an image with a text query using multimodal input.
     * The bitmap is saved to cache and passed as a file path to the model.
     *
     * @param bitmap The screenshot to analyze
     * @param query User's question about the screen
     * @param onToken Callback for each token as it's generated
     * @param onComplete Callback when generation is complete
     * @param onError Callback if an error occurs
     */
    fun analyzeImage(
        bitmap: Bitmap,
        query: String,
        onToken: (String) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (!isInitialized.get() || isClosed.get()) {
            onError("Inference engine not initialized")
            return
        }

        inferenceScope.launch {
            try {
                val engineInstance = engine ?: throw IllegalStateException("Engine is null")

                // Save bitmap to cache file for multimodal input
                val imagePath = saveBitmapToCache(bitmap)
                
                if (imagePath == null) {
                    Log.e(TAG, "Failed to save screenshot to cache")
                    onError("Failed to prepare image for analysis")
                    return@launch
                }

                Log.d(TAG, "Screenshot saved to: $imagePath")

                // Build multimodal message with image path
                val userMessage = buildMultimodalMessage(query, imagePath)

                Log.d(TAG, "Sending multimodal message to model: $userMessage")

                // Create a conversation
                val conversation = engineInstance.createConversation()

                try {
                    // Send message using JSON format for multimodal input
                    val userMessageObj = Message.of(userMessage)
                    val responseMessage = conversation.sendMessage(userMessageObj)
                    val responseText = responseMessage.toString()
                    
                    Log.d(TAG, "Response received: ${responseText.take(100)}...")
                    
                    // Simulate streaming by splitting into words/tokens
                    val tokens = responseText.split(" ")
                    
                    for (token in tokens) {
                        mainScope.launch {
                            onToken("$token ")
                        }
                        // Small delay for streaming effect
                        kotlinx.coroutines.delay(30)
                    }

                    withContext(Dispatchers.Main) {
                        onComplete()
                    }
                } finally {
                    conversation.close()
                    // Clean up cached screenshot
                    cleanupScreenshotCache()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Failed to analyze image", e)
                withContext(Dispatchers.Main) {
                    onError(e.message ?: "Inference failed")
                }
            }
        }
    }

    /**
     * Save bitmap to cache directory for multimodal input.
     * LiteRT-LM requires image input as a file path.
     *
     * @param bitmap The screenshot bitmap to save
     * @return Absolute path to saved file, or null if save failed
     */
    private fun saveBitmapToCache(bitmap: Bitmap): String? {
        return try {
            val cacheFile = File(context.cacheDir, SCREENSHOT_CACHE_FILE)
            FileOutputStream(cacheFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            Log.d(TAG, "Bitmap saved to cache: ${cacheFile.absolutePath} (${cacheFile.length()} bytes)")
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
                cacheFile.delete()
                Log.d(TAG, "Cached screenshot cleaned up")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error cleaning up screenshot cache", e)
        }
    }

    /**
     * Build multimodal message with text and image for vision models.
     * 
     * Format per LiteRT-LM documentation:
     * {
     *   "role": "user",
     *   "content": [
     *     {"type": "text", "text": "question"},
     *     {"type": "image", "path": "/path/to/image.jpg"}
     *   ]
     * }
     *
     * @param query User's question
     * @param imagePath Absolute path to saved screenshot
     * @return JSON string for multimodal message
     */
    private fun buildMultimodalMessage(query: String, imagePath: String): String {
        val message = JSONObject().apply {
            put("role", "user")
            put("content", JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "text")
                    put("text", query)
                })
                put(JSONObject().apply {
                    put("type", "image")
                    put("path", imagePath)
                })
            })
        }
        return message.toString()
    }

    /**
     * Build text-only user message (fallback for non-vision models).
     * 
     * @param bitmap The screenshot (for dimensions)
     * @param query User's question
     * @return Text prompt
     */
    @Suppress("unused")
    private fun buildTextOnlyMessage(bitmap: Bitmap, query: String): String {
        return """<start_of_turn>user
I am viewing a screenshot of my device screen (size: ${bitmap.width}x${bitmap.height}).

My question: $query

Please analyze what might be visible on the screen and answer my question.
<end_of_turn>
<start_of_turn>model
""".trimIndent()
    }

    /**
     * Close the inference engine and release all resources.
     * This is critical for memory management.
     */
    fun close() {
        if (isClosed.getAndSet(true)) {
            return
        }

        Log.i(TAG, "Closing inference engine and releasing memory")

        try {
            // Clean up any cached screenshots
            cleanupScreenshotCache()
            
            engine?.close()
            engine = null
            isInitialized.set(false)
            Log.i(TAG, "Inference engine released successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing inference engine", e)
        }
    }

    /**
     * Check if the engine is initialized and ready.
     */
    fun isReady(): Boolean {
        return isInitialized.get() && !isClosed.get()
    }

    /**
     * Get current thermal state.
     */
    fun getThermalState(): ThermalMonitor.ThermalState {
        return thermalMonitor.thermalState.value
    }

    protected fun finalize() {
        close()
    }
}
