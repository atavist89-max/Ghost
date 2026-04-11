package com.ghost.app.utils

import java.io.File

/**
 * File path constants for Ghost application.
 * All paths are relative to external storage root.
 */
object GhostPaths {

    /**
     * Base directory for Ghost application data.
     */
    private const val BASE_DIR = "/sdcard/Download/GhostModels"

    /**
     * Path to the LLM model file.
     * User must place the 2.5GB .litertlm file here before using the app.
     */
    const val MODEL_PATH = "$BASE_DIR/gemma-4-e2b.litertlm"

    /**
     * Minimum model file size in bytes (2GB).
     * Used to validate model file integrity.
     */
    const val MIN_MODEL_SIZE_BYTES = 2_000_000_000L

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
    fun getDisplayPath(): String = "Internal Storage/Downloads/GhostModels/"
}
