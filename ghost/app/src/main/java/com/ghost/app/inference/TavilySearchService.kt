package com.ghost.app.inference

import android.content.Context
import android.os.Environment
import android.util.Log
import com.ghost.app.utils.GhostPaths
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Tavily Search API service with diagnostic logging.
 * FIX: Removed 'by lazy' caching that was causing empty key to be cached permanently.
 */
class TavilySearchService(private val context: Context) {

    companion object {
        private const val TAG = "TavilyTTS"
        private const val KEY_FILENAME = "tavily_key.txt"
    }

    private fun logToFile(tag: String, message: String) {
        try {
            val logFile = File("/storage/emulated/0/Download/GhostModels/debug_log.txt")
            logFile.parentFile?.mkdirs()
            val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            logFile.appendText("[$timestamp] [$tag] $message\n")
        } catch (e: Exception) {
            // Silent fail - don't crash if logging fails
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    // FIX: Changed from 'by lazy' to getter - reads fresh each time
    internal val apiKey: String get() = readApiKeyFromStorage()

    // Client-side credit tracking
    private var clientSideCredits = 1000

    fun deductCredits(amount: Int) {
        clientSideCredits -= amount
    }

    fun getCreditsForDisplay(): Int = clientSideCredits

    /**
     * Read API key with multiple fallback paths and detailed logging.
     */
    private fun readApiKeyFromStorage(): String {
        Log.d(TAG, "=== Starting API key read ===")

        return try {
            // Try multiple paths in order
            val pathsToTry = listOf(
                GhostPaths.findModelFile()?.parentFile,
                File(GhostPaths.MODEL_PATH).parentFile,
                Environment.getExternalStorageDirectory()?.resolve("Download")?.resolve("GhostModels"),
                File("/storage/emulated/0/Download/GhostModels"),
                context.getExternalFilesDir(null)?.resolve("models"),
                context.filesDir.resolve("models")
            )

            for (dir in pathsToTry.filterNotNull().distinctBy { it.absolutePath }) {
                val keyFile = File(dir, KEY_FILENAME)
                Log.d(TAG, "Checking path: ${keyFile.absolutePath}")
                Log.d(TAG, "  exists=${keyFile.exists()}, canRead=${keyFile.canRead()}, size=${keyFile.length()}")

                if (keyFile.exists() && keyFile.canRead()) {
                    val content = keyFile.readText()
                    val key = content.trim()
                    Log.i(TAG, "FOUND KEY at: ${keyFile.absolutePath}")
                    Log.i(TAG, "  Raw length: ${content.length}, trimmed length: ${key.length}")
                    Log.i(TAG, "  Starts with: ${key.take(20)}...")
                    return key
                }
            }

            Log.w(TAG, "Key file NOT FOUND in any location")
            ""

        } catch (e: Exception) {
            Log.e(TAG, "Exception reading key: ${e.message}", e)
            ""
        }
    }

    fun isConfigured(): Boolean {
        val key = apiKey  // Triggers fresh read
        val isBlank = key.isBlank()
        val startsWithTvly = key.startsWith("tvly-")
        val configured = !isBlank && startsWithTvly

        Log.d(TAG, "isConfigured() check: isBlank=$isBlank, startsWithTvly=$startsWithTvly, RESULT=$configured")
        if (configured) {
            Log.d(TAG, "  Key preview: ${key.take(15)}...")
        }
        return configured
    }

    data class SearchRequest(
        @SerializedName("api_key") val apiKey: String,
        val query: String,
        @SerializedName("search_depth") val searchDepth: String = "basic",
        @SerializedName("max_results") val maxResults: Int = 3,
        @SerializedName("include_answer") val includeAnswer: Boolean = true,
        @SerializedName("include_images") val includeImages: Boolean = false
    )

    data class SearchResponse(
        val query: String,
        val answer: String?,
        val results: List<SearchResult>?,
        @SerializedName("response_time") val responseTime: Double?
    )

    data class SearchResult(
        val title: String,
        val url: String,
        val content: String,
        val score: Double?
    )

    suspend fun search(query: String): Pair<String, Int?> = withContext(Dispatchers.IO) {
        val (result, credits) = searchRaw(query)

        val contextBuilder = StringBuilder()
        contextBuilder.append("WEB SEARCH RESULTS:\n")
        result.results?.take(3)?.forEachIndexed { index, resultItem ->
            contextBuilder.append("[${index + 1}] ${resultItem.title}\n")
            contextBuilder.append("    ${resultItem.content.take(200)}...\n\n")
        }

        Pair(contextBuilder.toString().trim(), credits)
    }

    suspend fun searchRaw(query: String): Pair<SearchResponse, Int?> = withContext(Dispatchers.IO) {
        logToFile("TAVILY", "Query: $query")

        if (!isConfigured()) {
            Log.e(TAG, "search() aborted: API not configured")
            throw IllegalStateException("Tavily API key not configured")
        }

        val requestBody = SearchRequest(
            apiKey = apiKey,
            query = query,
            searchDepth = "basic",
            maxResults = 3,
            includeAnswer = false
        )

        val json = gson.toJson(requestBody)
        val body = json.toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("https://api.tavily.com/search")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            // LOG ALL HEADERS
            val headers = StringBuilder()
            response.headers.forEach { (name, value) -> headers.append("$name: $value\n") }
            logToFile("TAVILY_HEADERS", headers.toString())

            val remainingCredits = response.header("X-RateLimit-Remaining")?.toIntOrNull()
            logToFile("TAVILY", "Rate limit: $remainingCredits")

            if (!response.isSuccessful) {
                logToFile("TAVILY_ERROR", "HTTP ${response.code}")
                throw IOException("Tavily API error: ${response.code}")
            }

            val responseBody = response.body?.string() ?: throw IOException("Empty response")
            logToFile("TAVILY_RAW", "Response length: ${responseBody.length}")

            val result = gson.fromJson(responseBody, SearchResponse::class.java)
            logToFile("TAVILY", "Results: ${result.results?.size ?: 0}, Answer: ${result.answer != null}")

            Pair(result, remainingCredits)
        }
    }
}
