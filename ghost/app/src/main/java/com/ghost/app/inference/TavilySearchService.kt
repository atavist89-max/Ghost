package com.ghost.app.inference

import android.content.Context
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
 * Tavily Search API service for web-enhanced local LLM queries.
 * Reads API key from external storage (GhostModels folder) to allow
 * key rotation without APK rebuild.
 */
class TavilySearchService(private val context: Context) {

    companion object {
        private const val TAG = "TavilySearch"
        private const val KEY_FILENAME = "tavily_key.txt"
        private const val DEFAULT_KEY = ""
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    // Read key from external storage instead of BuildConfig
    private val apiKey: String by lazy {
        readApiKeyFromStorage()
    }

    /**
     * Read API key from GhostModels folder using same logic as GhostPaths.
     * Location: /sdcard/Download/GhostModels/tavily_key.txt
     */
    private fun readApiKeyFromStorage(): String {
        return try {
            // Use same path resolution as GhostPaths.findModelFile()
            val modelsDir = GhostPaths.findModelFile()?.parentFile
                ?: File(GhostPaths.MODEL_PATH).parentFile
                ?: context.getExternalFilesDir(null)?.resolve("models")
                ?: context.filesDir.resolve("models")

            val keyFile = File(modelsDir, KEY_FILENAME)

            if (keyFile.exists() && keyFile.canRead()) {
                val key = keyFile.readText().trim()
                Log.i(TAG, "Tavily API key loaded from: ${keyFile.absolutePath}")
                key
            } else {
                Log.w(TAG, "$KEY_FILENAME not found in ${modelsDir.absolutePath}")
                DEFAULT_KEY
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read Tavily API key: ${e.message}", e)
            DEFAULT_KEY
        }
    }

    fun isConfigured(): Boolean = apiKey.isNotBlank() && apiKey.startsWith("tvly-")

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

    /**
     * Perform web search via Tavily API.
     */
    suspend fun search(query: String): Pair<String, Int?> = withContext(Dispatchers.IO) {
        if (!isConfigured()) {
            throw IllegalStateException("Tavily API key not configured. Add $KEY_FILENAME to GhostModels folder.")
        }

        val requestBody = SearchRequest(
            apiKey = apiKey,
            query = query,
            searchDepth = "basic",
            maxResults = 3,
            includeAnswer = true
        )

        val json = gson.toJson(requestBody)
        val body = json.toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("https://api.tavily.com/search")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Tavily API error: ${response.code} ${response.message}")
            }

            val remainingCredits = response.header("X-RateLimit-Remaining")?.toIntOrNull()

            val responseBody = response.body?.string()
                ?: throw IOException("Empty response from Tavily")

            val searchResult = gson.fromJson(responseBody, SearchResponse::class.java)

            val contextBuilder = StringBuilder()
            contextBuilder.append("WEB SEARCH RESULTS:\n")

            searchResult.answer?.let {
                contextBuilder.append("Summary: $it\n\n")
            }

            searchResult.results?.take(3)?.forEachIndexed { index, result ->
                contextBuilder.append("[${index + 1}] ${result.title}\n")
                contextBuilder.append("    ${result.content.take(200)}...\n\n")
            }

            Pair(contextBuilder.toString().trim(), remainingCredits)
        }
    }
}
