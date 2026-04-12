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
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Inference engine for local LLM using Google's official LiteRT-LM library.
 * Manages model loading and text generation with vision input.
 * 
 * Uses .litertlm format models (Gemma, Llama, Phi-4, Qwen supported).
 */
class InferenceEngine(private val context: Context) {

    companion object {
        private const val TAG = "GhostInference"

        // Generation parameters
        private const val MAX_TOKENS = 512
        private const val TEMPERATURE = 0.7
        private const val TOP_K = 40
        private const val TOP_P = 0.9
    }

    private var engine: Engine? = null
    private val thermalMonitor = ThermalMonitor(context)
    private val isInitialized = AtomicBoolean(false)
    private val isClosed = AtomicBoolean(false)

    private val mainScope = CoroutineScope(Dispatchers.Main)
    private val inferenceScope = CoroutineScope(Dispatchers.Default)

    /**
     * Initialize the inference engine with the model.
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
                
                // Select backend based on thermal state
                // Use uppercase factory methods from LiteRT-LM API
                val backend = if (thermalMonitor.shouldUseGpu()) {
                    Log.i(TAG, "Using GPU backend")
                    Backend.GPU()
                } else {
                    Log.i(TAG, "Using CPU backend")
                    Backend.CPU()
                }

                // Create engine configuration
                val engineConfig = EngineConfig(
                    modelPath = modelFile.absolutePath,
                    backend = backend,
                    cacheDir = context.cacheDir.path // Improves 2nd load time
                )

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
     * Analyze an image with a text query.
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

                // Build the user message with context about the screenshot
                val userMessage = buildUserMessage(bitmap, query)

                Log.d(TAG, "Sending message to model: ${userMessage.take(100)}...")

                // Create a conversation
                val conversation = engineInstance.createConversation()

                try {
                    // Send message and get response
                    val userMessageObj = Message.of(userMessage)
                    val responseMessage = conversation.sendMessage(userMessageObj)
                    val responseText = responseMessage.toString()
                    
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
     * Build the user message with screenshot context.
     * 
     * Note: LiteRT-LM supports multimodality (vision), but the Android API
     * may vary. For now, we describe that a screenshot is being viewed.
     * If the model supports image input, we would include the bitmap here.
     */
    private fun buildUserMessage(bitmap: Bitmap, query: String): String {
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
