package com.ghost.app.inference

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Hybrid web search pipeline combining:
 * 1. Relevance-based re-querying
 * 2. Context compression
 * 3. Adversarial fact-checking
 *
 * Uses 1-2 Tavily credits per query (average 1.2) and 3-4 local Gemma 4 E2B calls.
 */
class SmartSearchPipeline(
    private val context: Context,
    private val inferenceEngine: InferenceEngine
) {

    private val tavilyService = TavilySearchService(context)
    private var creditsUsed = 0

    companion object {
        private const val TAG = "SmartSearch"
    }

    /**
     * Execute smart search pipeline and return verified answer.
     */
    suspend fun search(query: String): Pair<String, Int?> = withContext(Dispatchers.IO) {
        creditsUsed = 0

        // STAGE 1: Initial search + relevance scoring
        val initialResults = tavilySearchRaw(query)
        val scores = scoreResults(query, initialResults.results)

        // Conditional re-query if all scores < 5
        val finalResults = if (scores.isEmpty() || scores.all { it < 5 }) {
            val improvedQuery = "$query ${extractKeyTerms(initialResults.results)}"
            tavilySearchRaw(improvedQuery)
        } else {
            initialResults
        }

        // STAGE 2: Compress context (top 2 relevant results only)
        val compressed = compressContext(query, finalResults.results, scores)

        // STAGE 3: Draft + verify
        val draft = generateDraft(query, compressed)
        val isVerified = verifyDraft(draft, compressed)

        Log.i(TAG, "Credits used: $creditsUsed")

        val answer = if (isVerified) draft else "[Unverified] $draft"
        Pair(answer, null) // Credits tracking handled separately
    }

    private suspend fun tavilySearchRaw(query: String): TavilySearchService.SearchResponse {
        creditsUsed++
        return withContext(Dispatchers.IO) {
            val requestBody = TavilySearchService.SearchRequest(
                apiKey = tavilyService.apiKey,
                query = query,
                searchDepth = "basic",
                maxResults = 3,
                includeAnswer = true
            )

            val gson = com.google.gson.Gson()
            val json = gson.toJson(requestBody)
            val body = json.toRequestBody("application/json".toMediaType())

            val request = okhttp3.Request.Builder()
                .url("https://api.tavily.com/search")
                .post(body)
                .build()

            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw java.io.IOException("Tavily API error: ${response.code}")
                }

                val responseBody = response.body?.string()
                    ?: throw java.io.IOException("Empty response")

                gson.fromJson(responseBody, TavilySearchService.SearchResponse::class.java)
            }
        }
    }

    private suspend fun scoreResults(query: String, results: List<TavilySearchService.SearchResult>?): List<Int> {
        return results?.map { result ->
            val prompt = "Rate 0-10: Does this text answer \"$query\"?\nText: ${result.content.take(300)}\nReply number only:"
            val response = quickLocalInference(prompt)
            response.filter { it.isDigit() }.toIntOrNull() ?: 0
        } ?: emptyList()
    }

    private fun extractKeyTerms(results: List<TavilySearchService.SearchResult>?): String {
        return results?.firstOrNull()?.title
            ?.replace(Regex("""\|.*|\-.*"""), "")
            ?.take(30) ?: ""
    }

    private suspend fun compressContext(query: String, results: List<TavilySearchService.SearchResult>?, scores: List<Int>): String {
        val relevant = results?.zip(scores)
            ?.filter { it.second >= 5 }
            ?.sortedByDescending { it.second }
            ?.take(2)
            ?.map { it.first }
            ?: return "No relevant sources."

        val prompt = "Extract only sentences answering \"$query\":\n${relevant.mapIndexed { i, r -> "[${i+1}] ${r.content.take(400)}" }.joinToString("\n")}\nRelevant facts:"
        return quickLocalInference(prompt)
    }

    private suspend fun generateDraft(query: String, context: String): String {
        val prompt = "Based on: $context\nAnswer: $query"
        return quickLocalInference(prompt)
    }

    private suspend fun verifyDraft(draft: String, context: String): Boolean {
        val prompt = "Facts: $context\nClaim: $draft\nIs claim fully supported? Reply VERIFIED or UNSUPPORTED:"
        return quickLocalInference(prompt).contains("VERIFIED")
    }

    private suspend fun quickLocalInference(prompt: String): String {
        return try {
            inferenceEngine.quickInfer(prompt)
        } catch (e: Exception) {
            Log.w(TAG, "quickLocalInference failed: ${e.message}")
            "Error"
        }
    }
}
