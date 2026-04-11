package com.ghost.app.inference

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.ghost.app.utils.GhostPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import llama.LlamaContext
import llama.LlamaModel
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Inference engine for local LLM using llama.cpp.
 * Manages model loading and text generation with vision input.
 * 
 * Uses GGUF format models which are the standard for llama.cpp.
 * User should place a .gguf model file in the GhostModels directory.
 */
class InferenceEngine(private val context: Context) {

    companion object {
        private const val TAG = "GhostInference"

        // Generation parameters
        private const val MAX_TOKENS = 512
        private const val TEMPERATURE = 0.7f
        private const val TOP_K = 40
        private const val TOP_P = 0.9f

        // Image processing
        private const val IMAGE_INPUT_SIZE = 224
    }

    private var llamaContext: LlamaContext? = null
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
                // Try to find any compatible model file
                val modelFile = GhostPaths.findModelFile()
                    ?: throw IllegalStateException("No model file found. ${GhostPaths.getModelDownloadInstructions()}")

                // Log model file info for debugging
                Log.i(TAG, "Loading model from: ${modelFile.absolutePath}")
                Log.i(TAG, "Model exists: ${modelFile.exists()}")
                Log.i(TAG, "Model size: ${modelFile.length()} bytes")

                if (!modelFile.exists()) {
                    throw IllegalStateException("Model file not found at ${modelFile.absolutePath}")
                }

                // Load the model
                val model = LlamaModel(modelFile.absolutePath, nCtx = 2048)
                llamaContext = LlamaContext(model)

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
                // Check thermal status
                thermalMonitor.checkThermalStatus()

                val context = llamaContext ?: throw IllegalStateException("LlamaContext is null")

                // Build prompt with image context
                val prompt = buildPrompt(query)

                Log.d(TAG, "Starting inference with prompt: ${prompt.take(50)}...")

                // Generate response with streaming
                var generatedTokens = 0
                
                context.generate(
                    prompt = prompt,
                    maxTokens = MAX_TOKENS,
                    temperature = TEMPERATURE,
                    topK = TOP_K,
                    topP = TOP_P
                ) { token ->
                    mainScope.launch {
                        onToken(token)
                    }
                    generatedTokens++
                    generatedTokens < MAX_TOKENS
                }

                withContext(Dispatchers.Main) {
                    onComplete()
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
     * Build the prompt for the LLM.
     * For vision-capable models, we would include image tokens here.
     * For now, we describe that the user is viewing a screenshot.
     */
    private fun buildPrompt(query: String): String {
        return """<start_of_turn>user
You are viewing a screenshot of the user's device. Please analyze what you see and answer their question.

User's question: $query

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
            llamaContext?.close()
            llamaContext = null
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
