package com.ghost.app.inference

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.ghost.app.R
import com.ghost.app.utils.GhostPaths
import java.io.File

/**
 * Validates the LLM model file existence and integrity.
 */
object ModelValidator {

    private const val TAG = "GhostModelValidator"

    /**
     * Result of model validation.
     */
    sealed class ValidationResult {
        object Valid : ValidationResult()
        data class Invalid(val reason: String) : ValidationResult()
    }

    /**
     * Validate that the model file exists and meets size requirements.
     *
     * @return ValidationResult indicating if model is valid
     */
    fun validateModel(): ValidationResult {
        // First try to find any compatible model file
        val modelFile = GhostPaths.findModelFile() ?: GhostPaths.getModelFile()

        // Check if file exists
        if (!modelFile.exists()) {
            Log.e(TAG, "Model file not found at ${modelFile.absolutePath}")
            return ValidationResult.Invalid(
                "Model not found. ${GhostPaths.getModelDownloadInstructions()}"
            )
        }

        // Check if it's a file (not directory)
        if (!modelFile.isFile) {
            Log.e(TAG, "Model path is not a file")
            return ValidationResult.Invalid("Invalid model file type")
        }

        // Check file extension (should be .gguf for llama.cpp)
        if (!modelFile.name.endsWith(".gguf", ignoreCase = true)) {
            Log.e(TAG, "Model file is not .gguf format: ${modelFile.name}")
            return ValidationResult.Invalid(
                "Invalid model format: ${modelFile.extension}. Use .gguf format. " +
                "Note: .litertlm format is NOT compatible."
            )
        }

        // Check file size
        val fileSize = modelFile.length()
        if (fileSize < GhostPaths.MIN_MODEL_SIZE_BYTES) {
            Log.e(TAG, "Model file too small: $fileSize bytes")
            return ValidationResult.Invalid(
                "Model file appears to be corrupted (too small: ${formatFileSize(fileSize)})"
            )
        }

        // Check if file is readable
        if (!modelFile.canRead()) {
            Log.e(TAG, "Model file is not readable")
            return ValidationResult.Invalid("Cannot read model file (permission denied)")
        }

        Log.i(TAG, "Model validation passed: ${formatFileSize(fileSize)}")
        return ValidationResult.Valid
    }

    /**
     * Validate model and show Toast on failure.
     *
     * @param context Context for showing Toast
     * @return true if valid, false otherwise
     */
    fun validateModelWithFeedback(context: Context): Boolean {
        return when (val result = validateModel()) {
            is ValidationResult.Valid -> true
            is ValidationResult.Invalid -> {
                Toast.makeText(context, result.reason, Toast.LENGTH_LONG).show()
                false
            }
        }
    }

    /**
     * Get model file size in human-readable format.
     *
     * @return String like "2.5 GB"
     */
    fun getModelSizeString(): String {
        val file = GhostPaths.findModelFile() ?: GhostPaths.getModelFile()
        if (!file.exists()) return "0 MB"
        return formatFileSize(file.length())
    }

    /**
     * Check if model file exists without full validation.
     */
    fun modelExists(): Boolean {
        return GhostPaths.findModelFile()?.exists() == true
    }
    
    /**
     * Format file size to human readable string.
     */
    private fun formatFileSize(sizeBytes: Long): String {
        val sizeGB = sizeBytes / (1024.0 * 1024.0 * 1024.0)
        val sizeMB = sizeBytes / (1024.0 * 1024.0)

        return if (sizeGB >= 1.0) {
            String.format("%.2f GB", sizeGB)
        } else {
            String.format("%.0f MB", sizeMB)
        }
    }
}
