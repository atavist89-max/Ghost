package com.ghost.app.inference

import android.util.Log
import com.ghost.app.BuildConfig
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Tavily Search API service for web-enhanced local LLM queries.
 *
 * Architecture: Search -> Local LLM (not full cloud)
 * - Query goes to Tavily API
 * - Search results injected into local Gemma prompt as context
 * - Screenshot stays local, never uploaded to Tavily
 *
 * Free tier: 1,000 credits/month, resets 1st of each month
 * Credits displayed in UI only after first successful search
 */
class TavilySearchService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val apiKey = BuildConfig.TAVILY_API_KEY

    data class SearchRequest(
        @SerializedName("api_key") val apiKey: String,
        val query: String,
        @SerializedName("search_depth") val searchDepth: String = "basic", // "basic" = 1 credit, "advanced" = 2 credits
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
     *
     * @param query User's search query
     * @return Pair of (search context string for LLM prompt, remaining credits from header)
     */
    suspend fun search(query: String): Pair<String, Int?> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            throw IllegalStateException("Tavily API key not configured")
        }

        val requestBody = SearchRequest(
            apiKey = apiKey,
            query = query,
            searchDepth = "basic", // 1 credit per search
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

            // Extract remaining credits from header
            val remainingCredits = response.header("X-RateLimit-Remaining")?.toIntOrNull()

            val responseBody = response.body?.string()
                ?: throw IOException("Empty response from Tavily")

            val searchResult = gson.fromJson(responseBody, SearchResponse::class.java)

            // Build context string for LLM prompt
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

    fun isConfigured(): Boolean = apiKey.isNotBlank() && apiKey.startsWith("tvly-")
}
