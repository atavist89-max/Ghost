package com.ghost.app.inference

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import com.ghost.app.utils.DebugLogger
import com.ghost.app.utils.GhostPaths
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import org.json.JSONObject
import java.io.File
import java.io.FileWriter

/**
 * Piper TTS service for HAL 9000 voice synthesis.
 * Uses Sherpa-ONNX to run inference with the converted Piper model.
 *
 * BEFORE USING: The raw Piper model must be converted.
 * Run scripts/convert_hal_model.py on your desktop to generate tokens.txt
 * and patch ONNX metadata, then copy the files to the Android device.
 */
class PiperTTS(private val context: Context) {

    companion object {
        private const val TAG = "PiperTTS"
        private const val MODEL_FILENAME = "hal.onnx"
        private const val CONFIG_FILENAME = "hal.onnx.json"
    }

    private var mediaPlayer: MediaPlayer? = null
    private var tts: OfflineTts? = null
    private var isInitialized = false
    private var sampleRate: Int = 22050 // Default, override from JSON

    /**
     * Initialize Piper with HAL 9000 model from model directory.
     * Reads JSON config, auto-generates tokens.txt if missing,
     * and validates that espeak-ng-data and converted ONNX exist.
     */
    fun initialize(): Boolean {
        return try {
            val modelDir = GhostPaths.findModelFile()?.parentFile
                ?: File(GhostPaths.MODEL_PATH).parentFile
                ?: context.getExternalFilesDir(null)?.resolve("models")

            val modelFile = File(modelDir, MODEL_FILENAME)
            val configFile = File(modelDir, CONFIG_FILENAME)
            val tokensFile = File(modelDir, "tokens.txt")
            val espeakDataDir = File(modelDir, "espeak-ng-data")

            val logMsg = "Looking for HAL model at: ${modelFile.absolutePath}"
            Log.i(TAG, logMsg)
            DebugLogger.i(TAG, logMsg)

            if (!modelFile.exists()) {
                Log.e(TAG, "HAL 9000 model not found. Expected: ${modelFile.absolutePath}")
                return false
            }

            if (!configFile.exists()) {
                Log.w(TAG, "JSON config not found, using defaults (sample_rate=22050)")
            } else {
                val jsonText = configFile.readText()
                val configJson = JSONObject(jsonText)

                sampleRate = configJson.optInt("sample_rate", 22050)

                val configMsg = "HAL config loaded: sample_rate=$sampleRate"
                Log.i(TAG, configMsg)
                DebugLogger.i(TAG, configMsg)

                // Auto-generate tokens.txt from phoneme_id_map if missing
                if (!tokensFile.exists()) {
                    generateTokensTxt(configJson, tokensFile)
                }
            }

            if (!tokensFile.exists()) {
                val err = "tokens.txt missing. Convert the Piper model first: python3 scripts/convert_hal_model.py ${modelDir?.absolutePath}"
                Log.e(TAG, err)
                DebugLogger.e(TAG, err)
                return false
            }

            if (!espeakDataDir.exists() || !espeakDataDir.isDirectory) {
                val err = "espeak-ng-data directory missing at ${espeakDataDir.absolutePath}. " +
                        "Download from https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/espeak-ng-data.tar.bz2 and extract here."
                Log.e(TAG, err)
                DebugLogger.e(TAG, err)
                return false
            }

            val inferenceJson = if (configFile.exists()) {
                JSONObject(configFile.readText()).optJSONObject("inference")
            } else null

            val noiseScale = inferenceJson?.optDouble("noise_scale", 0.667)?.toFloat() ?: 0.667f
            val lengthScale = inferenceJson?.optDouble("length_scale", 1.0)?.toFloat() ?: 1.0f
            val noiseW = inferenceJson?.optDouble("noise_w", 0.8)?.toFloat() ?: 0.8f

            val vitsConfig = OfflineTtsVitsModelConfig(
                model = modelFile.absolutePath,
                tokens = tokensFile.absolutePath,
                dataDir = espeakDataDir.absolutePath,
                noiseScale = noiseScale,
                noiseScaleW = noiseW,
                lengthScale = lengthScale
            )

            val ttsConfig = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = vitsConfig,
                    numThreads = 2,
                    debug = false,
                    provider = "cpu"
                )
            )

            tts = OfflineTts(assetManager = null, config = ttsConfig)
            isInitialized = true
            val initMsg = "Piper TTS initialized via Sherpa-ONNX (sampleRate=$sampleRate)"
            Log.i(TAG, initMsg)
            DebugLogger.i(TAG, initMsg)
            true

        } catch (e: Exception) {
            val errMsg = "Failed to initialize Piper TTS: ${e.message}"
            Log.e(TAG, errMsg, e)
            DebugLogger.e(TAG, errMsg, e)

            if (e.message?.contains("metadata", ignoreCase = true) == true ||
                e.message?.contains("comment", ignoreCase = true) == true ||
                e.message?.contains("piper", ignoreCase = true) == true
            ) {
                val conversionMsg = "The ONNX model may need conversion. Run: python3 scripts/convert_hal_model.py <model_dir>"
                Log.e(TAG, conversionMsg)
                DebugLogger.e(TAG, conversionMsg)
            }
            false
        }
    }

    /**
     * Generate tokens.txt from the phoneme_id_map in the Piper JSON config.
     * Sherpa-ONNX requires this mapping file to tokenize phonemes.
     */
    private fun generateTokensTxt(configJson: JSONObject, tokensFile: File) {
        val phonemeIdMap = configJson.optJSONObject("phoneme_id_map") ?: run {
            Log.w(TAG, "No phoneme_id_map found in JSON, cannot generate tokens.txt")
            return
        }

        FileWriter(tokensFile).use { writer ->
            val keys = phonemeIdMap.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val idArray = phonemeIdMap.optJSONArray(key)
                val id = idArray?.optInt(0, -1) ?: -1
                if (id >= 0) {
                    writer.write("$key $id\n")
                }
            }
        }
        val genMsg = "Generated tokens.txt at ${tokensFile.absolutePath}"
        Log.i(TAG, genMsg)
        DebugLogger.i(TAG, genMsg)
    }

    /**
     * Synthesize text to speech using HAL 9000 voice.
     * Runs inference on a background thread, saves to a WAV file,
     * and plays via MediaPlayer on the main thread.
     */
    fun speak(text: String, onComplete: () -> Unit, onError: (String) -> Unit) {
        if (!isInitialized || tts == null) {
            onError("Piper TTS not initialized")
            return
        }

        Thread {
            try {
                val outputFile = File(context.cacheDir, "hal_speech_${System.currentTimeMillis()}.wav")
                val audio = tts!!.generate(text = text, sid = 0, speed = 1.0f)

                if (audio.samples.isEmpty()) {
                    context.mainExecutor.execute {
                        onError("TTS generated empty audio. The model may need conversion.")
                    }
                    return@Thread
                }

                val saved = audio.save(outputFile.absolutePath)
                if (!saved) {
                    context.mainExecutor.execute {
                        onError("Failed to save generated audio to cache")
                    }
                    return@Thread
                }

                context.mainExecutor.execute {
                    playAudio(outputFile, onComplete, onError)
                }

            } catch (e: Exception) {
                context.mainExecutor.execute {
                    onError("Speech synthesis failed: ${e.message}")
                }
            }
        }.start()
    }

    fun stop() {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
    }

    /**
     * Release the Sherpa-ONNX TTS engine and all native resources.
     * Call this in onDestroy() of the hosting Activity.
     */
    fun release() {
        stop()
        try {
            tts?.free()
        } catch (e: Exception) {
            Log.w(TAG, "Error freeing TTS: ${e.message}")
        }
        tts = null
        isInitialized = false
    }

    private fun playAudio(file: File, onComplete: () -> Unit, onError: (String) -> Unit) {
        try {
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
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

    fun getSampleRate(): Int = sampleRate
}
