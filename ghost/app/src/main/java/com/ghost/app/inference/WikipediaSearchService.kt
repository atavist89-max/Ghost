package com.ghost.app.inference

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

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
     * @return Pair of (article title, article content) or nulls if no result
     */
    suspend fun search(query: String): Pair<String, String> = withContext(Dispatchers.IO) {
        val searchResults = performSearch(query)
        if (searchResults.isEmpty()) {
            throw IllegalStateException("No Wikipedia results found for: $query")
        }

        val bestResult = searchResults.first()
        val title = bestResult.title
        val content = fetchArticleContent(title)
            ?: throw IllegalStateException("Failed to fetch Wikipedia article: $title")

        Log.i(TAG, "Wikipedia article fetched: '$title' (${content.length} chars)")
        Pair(title, content)
    }

    private fun performSearch(query: String): List<SearchResult> {
        val url = HttpUrl.parse(API_ENDPOINT)?.newBuilder()
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
                throw IllegalStateException("Wikipedia search HTTP ${response.code()}")
            }
            val body = response.body()?.string()
                ?: throw IllegalStateException("Empty Wikipedia search response")

            val result = gson.fromJson(body, SearchResponse::class.java)
            return result.query?.search ?: emptyList()
        }
    }

    private fun fetchArticleContent(title: String): String? {
        val encodedTitle = URLEncoder.encode(title, "UTF-8")
        val url = HttpUrl.parse(API_ENDPOINT)?.newBuilder()
            ?.addQueryParameter("action", "query")
            ?.addQueryParameter("prop", "extracts")
            ?.addQueryParameter("explaintext", "true")
            ?.addQueryParameter("exsectionformat", "plain")
            ?.addQueryParameter("titles", encodedTitle)
            ?.addQueryParameter("format", "json")
            ?.addQueryParameter("origin", "*")
            ?.build()
            ?: return null

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "GhostApp/1.0 (Android)")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body()?.string() ?: return null

            val result = gson.fromJson(body, ExtractResponse::class.java)
            val page = result.query?.pages?.values?.firstOrNull() ?: return null
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
