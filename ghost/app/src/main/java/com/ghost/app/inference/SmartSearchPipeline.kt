package com.ghost.app.inference

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    private var creditsUsedThisQuery = 0

    // Plain text enforcement suffix for all prompts
    private val plainTextEnforcement = "CRITICAL: Use only plain text with no formatting. " +
        "Do not use asterisks, stars, bullet points, markdown, or any special characters for emphasis. " +
        "Write as if outputting to a 1970s monochrome terminal."

    /**
     * Execute smart search pipeline and return verified answer with remaining credits.
     */
    suspend fun search(query: String): Pair<String, Int> = withContext(Dispatchers.IO) {
        creditsUsedThisQuery = 0
        logToFile("PIPELINE", "Starting search: $query")

        // Stage 1: Initial search + relevance scoring
        val initialResults = tavilySearch(query)
        val scores = scoreResults(query, initialResults.results)

        // Conditional re-query if all scores < 5
        val finalResults = if (scores.isEmpty() || scores.all { it < 5 }) {
            val improvedQuery = "$query ${extractKeyTerms(initialResults.results)}"
            tavilySearch(improvedQuery)
        } else {
            initialResults
        }

        // Stage 2: Compress context (top 2 relevant results only)
        val compressed = compressContext(query, finalResults.results, scores)

        // Stage 3: Draft + verify
        val draft = generateDraft(query, compressed)
        val isVerified = verifyDraft(draft, compressed)

        // Track credits client-side
        tavilyService.deductCredits(creditsUsedThisQuery)
        val remaining = tavilyService.getCreditsForDisplay()

        logToFile("PIPELINE", "Search complete. Credits used: $creditsUsedThisQuery, remaining: $remaining")

        val answer = if (isVerified) draft else "[Unverified] $draft"
        Pair(answer, remaining)
    }

    private suspend fun tavilySearch(query: String): TavilySearchService.SearchResponse {
        creditsUsedThisQuery++
        val (response, _) = tavilyService.searchRaw(query)
        return response
    }

    private suspend fun scoreResults(query: String, results: List<TavilySearchService.SearchResult>?): List<Int> {
        return results?.map { result ->
            val prompt = """
                Rate 0-10: Does this text answer "$query"?
                Text: ${result.content.take(300)}
                $plainTextEnforcement
                Reply with only a number 0-10:
            """.trimIndent()
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

        val prompt = """
            Extract only sentences from these texts that help answer: "$query"

            ${relevant.mapIndexed { i, r -> "[${i+1}] ${r.content.take(400)}" }.joinToString("\n")}

            $plainTextEnforcement

            Relevant facts only:
        """.trimIndent()
        return quickLocalInference(prompt)
    }

    private suspend fun generateDraft(query: String, context: String): String {
        val prompt = """
            Based on these facts: $context

            Answer concisely: $query

            $plainTextEnforcement
        """.trimIndent()
        return quickLocalInference(prompt)
    }

    private suspend fun verifyDraft(draft: String, context: String): Boolean {
        val prompt = """
            Facts: $context
            Claim: $draft

            Is the claim fully supported by the facts?
            $plainTextEnforcement
            Reply VERIFIED or UNSUPPORTED:
        """.trimIndent()
        return quickLocalInference(prompt).contains("VERIFIED")
    }

    private suspend fun quickLocalInference(prompt: String): String {
        return try {
            inferenceEngine.quickInfer(prompt)
        } catch (e: Exception) {
            Log.w("SmartSearch", "quickLocalInference failed: ${e.message}")
            "Error"
        }
    }

    private fun logToFile(tag: String, message: String) {
        try {
            val logFile = File("/storage/emulated/0/Download/GhostModels/debug_log.txt")
            logFile.parentFile?.mkdirs()
            val timestamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            logFile.appendText("[$timestamp] [$tag] $message\n")
        } catch (e: Exception) { }
    }
}
