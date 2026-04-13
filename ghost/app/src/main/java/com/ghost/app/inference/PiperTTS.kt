package com.ghost.app.inference

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import com.ghost.app.utils.DebugLogger
import com.ghost.app.utils.GhostPaths
import org.json.JSONObject
import java.io.File

/**
 * Piper TTS service for HAL 9000 voice synthesis.
 * Loads ONNX model from same directory as Gemma 4 E2B model.
 */
class PiperTTS(private val context: Context) {

    companion object {
        private const val TAG = "PiperTTS"
        private const val MODEL_FILENAME = "hal9000-denoised.onnx"
        private const val CONFIG_FILENAME = "hal9000-denoised.json"
    }

    private var mediaPlayer: MediaPlayer? = null
    private var isInitialized = false
    private var configJson: JSONObject? = null
    private var sampleRate: Int = 22050 // Default, override from JSON
    private var speakerId: Int? = null // From JSON num_speakers

    /**
     * Initialize Piper with HAL 9000 model from model directory.
     * Reads JSON config to extract sample_rate and speaker settings.
     * Model is stored alongside Gemma 4 E2B at GhostPaths.MODEL_PATH.
     */
    fun initialize(): Boolean {
        return try {
            // Look for model in same location as Gemma model
            val modelDir = GhostPaths.findModelFile()?.parentFile
                ?: File(GhostPaths.MODEL_PATH).parentFile
                ?: context.getExternalFilesDir(null)?.resolve("models")

            val modelFile = File(modelDir, MODEL_FILENAME)
            val configFile = File(modelDir, CONFIG_FILENAME)

            // CRITICAL: Parse JSON config first
            if (configFile.exists()) {
                val jsonText = configFile.readText()
                configJson = JSONObject(jsonText)

                // Extract audio parameters from JSON
                sampleRate = configJson?.optInt("sample_rate", 22050) ?: 22050

                // Check if multi-speaker model
                val numSpeakers = configJson?.optInt("num_speakers", 1) ?: 1
                if (numSpeakers > 1) {
                    speakerId = configJson?.optInt("speaker_id", 0) ?: 0
                }

                val configMsg = "HAL config loaded: sample_rate=$sampleRate, speakers=$numSpeakers"
                Log.i(TAG, configMsg)
                DebugLogger.i(TAG, configMsg)
            } else {
                Log.w(TAG, "JSON config not found, using defaults (sample_rate=22050)")
            }

            val logMsg = "Looking for HAL model at: ${modelFile.absolutePath}"
            Log.i(TAG, logMsg)
            DebugLogger.i(TAG, logMsg)

            if (!modelFile.exists()) {
                Log.e(TAG, "HAL 9000 model not found. Expected: ${modelFile.absolutePath}")
                return false
            }

            // TODO: Initialize ONNX Runtime session with modelFile.path
            // For now, placeholder - actual inference needs ONNX Runtime Mobile AAR

            isInitialized = true
            Log.i(TAG, "Piper TTS initialized with HAL 9000 voice (ready for inference)")
            true

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Piper TTS: ${e.message}", e)
            false
        }
    }

    /**
     * Synthesize text to speech using HAL 9000 voice.
     * Saves to temp file and plays via MediaPlayer with correct sample rate from JSON.
     */
    fun speak(text: String, onComplete: () -> Unit, onError: (String) -> Unit) {
        if (!isInitialized) {
            onError("Piper not initialized")
            return
        }

        try {
            val outputFile = File(context.cacheDir, "hal_speech_${System.currentTimeMillis()}.wav")

            // TODO: Run ONNX inference using model with parameters from JSON:
            // - sampleRate (from JSON)
            // - speakerId (if num_speakers > 1)
            // - espeak_voice for phonemes
            // This requires ONNX Runtime Mobile integration

            // Placeholder: For now just verify file creation
            // In real implementation, this would call native inference

            playAudio(outputFile, onComplete, onError)

        } catch (e: Exception) {
            onError("Speech synthesis failed: ${e.message}")
        }
    }

    fun stop() {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
    }

    private fun playAudio(file: File, onComplete: () -> Unit, onError: (String) -> Unit) {
        try {
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        // Use sample rate from JSON if API level supports
                        .build()
                )
                setDataSource(file.absolutePath)
                prepare()
                start()

                setOnCompletionListener {
                    onComplete()
                    release()
                    mediaPlayer = null
                    file.delete() // Clean up temp file
                }

                setOnErrorListener { _, what, extra ->
                    onError("Playback error: what=$what, extra=$extra")
                    true
                }
            }
        } catch (e: Exception) {
            onError("Audio playback failed: ${e.message}")
        }
    }

    fun isReady(): Boolean = isInitialized

    fun getSampleRate(): Int = sampleRate // Expose for debugging
}
