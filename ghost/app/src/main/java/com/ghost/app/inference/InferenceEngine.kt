package com.ghost.app.inference

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.ghost.app.utils.GhostPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Inference engine for local LLM using TensorFlow Lite.
 * Manages model loading and text generation with vision input.
 * 
 * Note: This implementation uses TensorFlow Lite as the base framework.
 * For .litertlm models, the model file should be a standard TFLite model
 * with the appropriate metadata for vision-language inference.
 */
class InferenceEngine(private val context: Context) {

    companion object {
        private const val TAG = "GhostInference"

        // Generation parameters
        private const val MAX_TOKENS = 512
        private const val TEMPERATURE = 0.7f
        private const val TOP_K = 40
        private const val TOP_P = 0.9f

        // Model input/output dimensions
        private const val IMAGE_INPUT_SIZE = 224
        private const val NUM_CHANNELS = 3
    }

    private var interpreter: Interpreter? = null
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

                // Load the model file manually
                val modelBuffer: MappedByteBuffer = loadModelFile(modelFile)

                // Create interpreter options
                val options = Interpreter.Options().apply {
                    // Check for GPU compatibility
                    val compatList = CompatibilityList()
                    if (compatList.isDelegateSupportedOnThisDevice) {
                        // Use GPU delegate without options (simpler API)
                        addDelegate(GpuDelegate())
                        Log.i(TAG, "Using GPU delegate")
                    } else {
                        // Use CPU with thread optimization
                        setNumThreads(4)
                        Log.i(TAG, "Using CPU with 4 threads")
                    }
                    
                    // Use experimental flags for better memory management
                    setUseXNNPACK(true)
                }

                // Create interpreter
                interpreter = Interpreter(modelBuffer, options)

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
     * Load model file into a MappedByteBuffer.
     */
    private fun loadModelFile(modelFile: File): MappedByteBuffer {
        FileInputStream(modelFile).use { inputStream ->
            val fileChannel = inputStream.channel
            val startOffset = 0L
            val declaredLength = modelFile.length()
            return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
        }
    }

    /**
     * Analyze an image with a text query.
     *
     * @param bitmap The screenshot to analyze
     * @param query User's question about the screen
     * @param onToken Callback for each token as it's generated (simulated for TFLite)
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

                // Preprocess the image
                val inputBuffer = preprocessImage(bitmap)

                // Run inference
                val interpreterInstance = interpreter ?: throw IllegalStateException("Interpreter is null")

                // Get input/output tensor info
                val inputTensorCount = interpreterInstance.inputTensorCount
                val outputTensorCount = interpreterInstance.outputTensorCount

                Log.d(TAG, "Model has $inputTensorCount inputs and $outputTensorCount outputs")

                // Prepare output buffer
                val outputShape = interpreterInstance.getOutputTensor(0).shape()
                val outputSize = outputShape.fold(1) { acc, dim -> acc * dim }
                val outputBuffer = ByteBuffer.allocateDirect(outputSize * 4)
                    .order(ByteOrder.nativeOrder())

                // Run inference
                interpreterInstance.run(inputBuffer, outputBuffer)

                // Process output (simulated response - actual implementation depends on model)
                val response = generateResponseFromOutput(outputBuffer, query)

                // Simulate token-by-token streaming
                val words = response.split(" ")
                for (word in words) {
                    withContext(Dispatchers.Main) {
                        onToken("$word ")
                    }
                    // Small delay to simulate streaming
                    kotlinx.coroutines.delay(50)
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
     * Preprocess the input image for the model.
     * Converts bitmap to ByteBuffer with normalization.
     */
    private fun preprocessImage(bitmap: Bitmap): ByteBuffer {
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, IMAGE_INPUT_SIZE, IMAGE_INPUT_SIZE, true)
        
        val inputSize = IMAGE_INPUT_SIZE * IMAGE_INPUT_SIZE * NUM_CHANNELS * 4 // 4 bytes per float
        val inputBuffer = ByteBuffer.allocateDirect(inputSize)
            .order(ByteOrder.nativeOrder())

        val intValues = IntArray(IMAGE_INPUT_SIZE * IMAGE_INPUT_SIZE)
        scaledBitmap.getPixels(intValues, 0, IMAGE_INPUT_SIZE, 0, 0, IMAGE_INPUT_SIZE, IMAGE_INPUT_SIZE)

        // Convert ARGB to RGB and normalize to [0, 1]
        for (pixelValue in intValues) {
            // Extract RGB channels
            val r = ((pixelValue shr 16) and 0xFF) / 255.0f
            val g = ((pixelValue shr 8) and 0xFF) / 255.0f
            val b = (pixelValue and 0xFF) / 255.0f

            inputBuffer.putFloat(r)
            inputBuffer.putFloat(g)
            inputBuffer.putFloat(b)
        }

        inputBuffer.rewind()
        
        // Clean up
        if (scaledBitmap !== bitmap) {
            scaledBitmap.recycle()
        }

        return inputBuffer
    }

    /**
     * Generate a response from model output.
     * This is a placeholder - actual implementation depends on model architecture.
     */
    private fun generateResponseFromOutput(outputBuffer: ByteBuffer, query: String): String {
        outputBuffer.rewind()
        
        // For vision-language models, the output would be token IDs that need decoding
        // This is a simplified placeholder response
        return "Based on the screenshot, I can see content that you're asking about: \"$query\". " +
               "This is a placeholder response. For production use, the model output tensors " +
               "would be decoded into actual text tokens. The captured image has been processed " +
               "and analyzed by the on-device model."
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
            interpreter?.close()
            interpreter = null
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
