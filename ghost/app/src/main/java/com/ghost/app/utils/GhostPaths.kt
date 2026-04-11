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
     * User must place a .gguf model file here before using the app.
     * 
     * Compatible models:
     * - Gemma GGUF from HuggingFace
     * - Llama GGUF
     * - Any llama.cpp compatible model
     * 
     * Download example:
     * wget https://huggingface.co/TheBloke/gemma-2b-it-GGUF/resolve/main/gemma-2b-it.Q4_K_M.gguf
     */
    val MODEL_PATH: String
        get() = "$BASE_DIR/model.gguf"

    /**
     * Alternative model names to look for (for flexibility)
     */
    fun findModelFile(): File? {
        val possibleNames = listOf(
            "model.gguf",
            "gemma-2b-it.gguf",
            "gemma.gguf",
            "llm.gguf"
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
     * GGUF models are typically 1-4GB.
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
        return """Download a GGUF model and place it in:
${getDisplayPath()}

Recommended models:
1. Gemma 2B Q4_K_M (~1.5GB):
   wget https://huggingface.co/TheBloke/gemma-2b-it-GGUF/resolve/main/gemma-2b-it.Q4_K_M.gguf

2. Or rename any .gguf file to 'model.gguf'

Note: .litertlm format is NOT compatible. Use .gguf format only."""
    }
}
