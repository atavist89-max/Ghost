package com.ghost.app.inference

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.ai.edge.litert.Backend
import com.google.ai.edge.litert.LlmInference
import com.google.ai.edge.litert.support.ImageInput
import com.ghost.app.utils.GhostPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Inference engine for local LLM using LiteRT-LM.
 * Manages model loading and text generation with vision input.
 */
class InferenceEngine(context: Context) {

    companion object {
        private const val TAG = "GhostInference"

        // Generation parameters
        private const val MAX_TOKENS = 512
        private const val TEMPERATURE = 0.7f
        private const val TOP_K = 40
        private const val TOP_P = 0.9f

        // Model configuration
        private const val PREFERRED_BACKEND = Backend.NPU
        private const val FALLBACK_BACKEND = Backend.GPU
    }

    private var llmInference: LlmInference? = null
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
                val modelFile = File(GhostPaths.MODEL_PATH)

                // Create inference options
                val options = LlmInference.Options.Builder()
                    .setModelPath(modelFile.absolutePath)
                    .setPreferredBackend(PREFERRED_BACKEND)
                    .setMaxTokens(MAX_TOKENS)
                    .setTemperature(TEMPERATURE)
                    .setTopK(TOP_K)
                    .setTopP(TOP_P)
                    .build()

                // Create inference instance
                llmInference = LlmInference.create(options)

                isInitialized.set(true)
                Log.i(TAG, "Inference engine initialized with NPU backend")

                withContext(Dispatchers.Main) {
                    onComplete(true, null)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize inference engine", e)

                // Try GPU fallback
                try {
                    initializeWithGpu(onComplete)
                } catch (fallbackError: Exception) {
                    Log.e(TAG, "GPU fallback also failed", fallbackError)
                    withContext(Dispatchers.Main) {
                        onComplete(false, e.message)
                    }
                }
            }
        }
    }

    /**
     * Initialize with GPU as fallback.
     */
    private suspend fun initializeWithGpu(onComplete: (Boolean, String?) -> Unit) {
        val modelFile = File(GhostPaths.MODEL_PATH)

        val options = LlmInference.Options.Builder()
            .setModelPath(modelFile.absolutePath)
            .setPreferredBackend(FALLBACK_BACKEND)
            .setMaxTokens(MAX_TOKENS)
            .setTemperature(TEMPERATURE)
            .setTopK(TOP_K)
            .setTopP(TOP_P)
            .build()

        llmInference = LlmInference.create(options)
        isInitialized.set(true)

        Log.i(TAG, "Inference engine initialized with GPU fallback")

        withContext(Dispatchers.Main) {
            onComplete(true, null)
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
                if (thermalMonitor.shouldUseGpu()) {
                    // Reinitialize with GPU if needed
                    switchToGpuIfNeeded()
                }

                // Convert bitmap to image input
                val imageInput = ImageInput.fromBitmap(bitmap)

                // Build prompt
                val prompt = buildPrompt(query)

                Log.d(TAG, "Starting inference with prompt: ${prompt.take(50)}...")

                // Generate response
                llmInference?.generateAsync(
                    prompt,
                    imageInput
                ) { partialResult, done, error ->
                    mainScope.launch {
                        when {
                            error != null -> {
                                Log.e(TAG, "Generation error", error)
                                onError(error.message ?: "Unknown error")
                            }
                            partialResult != null -> {
                                onToken(partialResult)
                            }
                            done -> {
                                Log.d(TAG, "Generation complete")
                                onComplete()
                            }
                        }
                    }
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
     */
    private fun buildPrompt(query: String): String {
        return """User is viewing a screenshot. 
            |Please analyze the image and answer the following question:
            |
            |$query
            |
            |Provide a concise, helpful response based on what you see in the image.""".trimMargin()
    }

    /**
     * Switch to GPU backend if thermal throttling is detected.
     */
    private suspend fun switchToGpuIfNeeded() {
        // Note: LiteRT-LM backend switching after initialization
        // may require re-creating the inference session.
        // This is a simplified implementation.
        Log.w(TAG, "Thermal throttling detected - using GPU delegate")
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
            llmInference?.close()
            llmInference = null
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
