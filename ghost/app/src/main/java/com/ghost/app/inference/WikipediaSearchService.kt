package com.ghost.app.inference

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File


/**
 * Wikipedia search service using the MediaWiki API.
 * No API key required.
 */
class WikipediaSearchService(private val context: Context) {

    companion object {
        private const val TAG = "WikipediaSearch"
        private const val API_ENDPOINT = "https://en.wikipedia.org/w/api.php"
        private val gson = Gson()
        private val client = OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    /**
     * Search Wikipedia for the best matching article and return its full text.
     *
     * @param query User search query
     * @return Pair of (article title, article content)
     */
    suspend fun searchAndExtract(query: String): Pair<String, String> = withContext(Dispatchers.IO) {
        clearLogFile()
        logToFile("WIKI", "=== New Search ===")
        logToFile("WIKI", "Query: $query")

        val searchResults = performSearch(query)
        logToFile("WIKI", "Search returned ${searchResults.size} results")

        if (searchResults.isEmpty()) {
            throw IllegalStateException("No Wikipedia results found for: $query")
        }

        val bestResult = searchResults.first()
        logToFile("WIKI", "Best result title: ${bestResult.title}")

        val title = bestResult.title
        val content = fetchArticleContent(title)

        logToFile("WIKI", "Full article length: ${content.length} chars")

        // Truncate to fit within Gemma 4 E2B's ~4K token context window
        val truncatedContent = extractFirstSentences(content, 25)
        logToFile("WIKI", "Truncated to: ${truncatedContent.length} chars (~${truncatedContent.length / 4} tokens)")

        Pair(title, truncatedContent)
    }

    /**
     * Extract first N sentences to fit within LLM context budget.
     * Conservative target: ~2,000 tokens for article content.
     */
    private fun extractFirstSentences(text: String, maxSentences: Int = 25): String {
        val sentences = text.split(Regex("(?<=[.!?])\\s+"))
        return sentences.take(maxSentences).joinToString(" ")
    }

    /**
     * Fallback character-based truncation at sentence boundary.
     */
    private fun truncateArticle(text: String, maxChars: Int = 8000): String {
        return if (text.length > maxChars) {
            val truncated = text.take(maxChars)
            val lastSentenceEnd = truncated.lastIndexOfAny(charArrayOf('.', '!', '?'))
            if (lastSentenceEnd > 0) {
                truncated.substring(0, lastSentenceEnd + 1) + " [article truncated]"
            } else {
                truncated + "..."
            }
        } else {
            text
        }
    }

    private fun logToFile(tag: String, message: String) {
        try {
            val dir = File("/storage/emulated/0/Download/GhostModels")
            if (!dir.exists()) dir.mkdirs()

            val logFile = File(dir, "wikipedia_debug.txt")
            val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            logFile.appendText("[$timestamp] [$tag] $message\n")
            Log.d(tag, message)
        } catch (e: Exception) {
            Log.e("WikipediaSearch", "Failed to write log: ${e.message}")
        }
    }

    private fun clearLogFile() {
        try {
            val logFile = File("/storage/emulated/0/Download/GhostModels/wikipedia_debug.txt")
            logFile.delete()
        } catch (e: Exception) {
            // Silent fail
        }
    }

    private fun performSearch(query: String): List<SearchResult> {
        val url = API_ENDPOINT.toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("action", "query")
            ?.addQueryParameter("list", "search")
            ?.addQueryParameter("srsearch", query)
            ?.addQueryParameter("srlimit", "5")
            ?.addQueryParameter("format", "json")
            ?.addQueryParameter("origin", "*")
            ?.build()
            ?: throw IllegalStateException("Failed to build search URL")

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "GhostApp/1.0 (Android)")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Wikipedia search HTTP ${response.code}")
            }
            val body = response.body?.string()
                ?: throw IllegalStateException("Empty Wikipedia search response")

            val result = gson.fromJson(body, SearchResponse::class.java)
            return result.query?.search ?: emptyList()
        }
    }

    private fun fetchArticleContent(title: String): String {
        // Wikipedia uses underscores in titles; encode after replacing spaces
        val wikiTitle = title.replace(" ", "_")
        logToFile("WIKI", "Extracting article: '$title' -> wikiTitle: '$wikiTitle'")

        val url = API_ENDPOINT.toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("action", "query")
            ?.addQueryParameter("prop", "extracts")
            ?.addQueryParameter("explaintext", "true")
            ?.addQueryParameter("exsectionformat", "plain")
            ?.addQueryParameter("exsentences", "30")
            ?.addQueryParameter("titles", wikiTitle)
            ?.addQueryParameter("format", "json")
            ?.addQueryParameter("origin", "*")
            ?.build()
            ?: throw IllegalStateException("Failed to build article URL")

        logToFile("WIKI", "Request URL: $url")

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "GhostApp/1.0 (Android)")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Wikipedia article HTTP ${response.code}")
            }
            val body = response.body?.string()
                ?: throw IllegalStateException("Empty article response")

            val result = gson.fromJson(body, ExtractResponse::class.java)
            val page = result.query?.pages?.values?.firstOrNull()
                ?: throw IllegalStateException("No page found for: $title")

            logToFile("WIKI", "Got ${page.extract?.length ?: 0} chars for '${page.title}'")

            if (page.extract.isNullOrEmpty()) {
                throw IllegalStateException("Article '${page.title}' has no content")
            }
            return page.extract
        }
    }

    // --- Gson data classes ---

    private data class SearchResponse(
        @SerializedName("query") val query: QueryContainer?
    )

    private data class QueryContainer(
        @SerializedName("search") val search: List<SearchResult>?
    )

    private data class SearchResult(
        @SerializedName("title") val title: String,
        @SerializedName("snippet") val snippet: String?
    )

    private data class ExtractResponse(
        @SerializedName("query") val query: ExtractQuery?
    )

    private data class ExtractQuery(
        @SerializedName("pages") val pages: Map<String, Page>?
    )

    private data class Page(
        @SerializedName("title") val title: String,
        @SerializedName("extract") val extract: String?
    )
}
