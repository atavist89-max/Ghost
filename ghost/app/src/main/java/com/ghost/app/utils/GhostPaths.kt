package com.ghost.app.utils

import android.os.Environment
import java.io.File

/**
 * File path constants for Ghost application.
 * All paths are relative to external storage root.
 */
object GhostPaths {

    /**
     * Base directory for Ghost application data.
     * Uses the actual external storage path instead of legacy /sdcard symlink.
     */
    private val BASE_DIR: String
        get() = File(Environment.getExternalStorageDirectory(), "Download/GhostModels").absolutePath

    /**
     * Path to the LLM model file.
     * User must place the .litertlm file here before using the app.
     * 
     * Compatible models:
     * - Gemma (all variants) from HuggingFace litert-community
     * - Llama, Phi-4, Qwen in .litertlm format
     * 
     * Download example:
     * wget https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm
     */
    val MODEL_PATH: String
        get() = "$BASE_DIR/gemma-4-e2b.litertlm"

    /**
     * Alternative model names to look for (for flexibility)
     */
    fun findModelFile(): File? {
        val possibleNames = listOf(
            "gemma-4-e2b.litertlm",
            "model.litertlm",
            "gemma.litertlm",
            "llm.litertlm"
        )
        
        for (name in possibleNames) {
            val file = File(BASE_DIR, name)
            if (file.exists()) {
                return file
            }
        }
        return null
    }

    /**
     * Minimum model file size in bytes (500MB).
     * LiteRT-LM models are typically 1-5GB.
     */
    const val MIN_MODEL_SIZE_BYTES = 500_000_000L

    /**
     * Get the model file reference.
     * @return File object pointing to the expected model location
     */
    fun getModelFile(): File = File(MODEL_PATH)

    /**
     * Check if the model directory exists, create if needed.
     * @return true if directory exists or was created successfully
     */
    fun ensureModelDirectory(): Boolean {
        val dir = File(BASE_DIR)
        return if (dir.exists()) {
            dir.isDirectory
        } else {
            dir.mkdirs()
        }
    }

    /**
     * Get human-readable model path for display purposes.
     */
    fun getDisplayPath(): String = "Internal Storage/Download/GhostModels/"
    
    /**
     * Get instructions for downloading a compatible model.
     */
    fun getModelDownloadInstructions(): String {
        return """Download a .litertlm model and place it in:
${getDisplayPath()}

Recommended models:
1. Gemma 4 E2B (~2.5GB):
   wget https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm

2. Or rename any .litertlm file to 'gemma-4-e2b.litertlm'

The app uses Google's official LiteRT-LM library for .litertlm models."""
    }
}
