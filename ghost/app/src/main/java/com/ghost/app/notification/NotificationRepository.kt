package com.ghost.app.notification

import android.content.Context
import android.database.Cursor
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/**
 * Data class representing a single notification row.
 */
data class NotificationEntry(
    val id: Long,
    val timestamp: Long,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val body: String,
    val category: String,
    val priority: Int,
    val isGroupSummary: Int,
    val dayOfWeek: Int,
    val hourOfDay: Int
)

/**
 * Result of applying the day-boundary greedy token filter on a list of notifications.
 */
data class DayBoundaryFilterResult(
    val analyzedEntries: List<NotificationEntry>,
    val cutoffDate: LocalDate?,
    val totalMatches: Int,
    val analyzedCount: Int
)

/**
 * Repository for querying notification history with token-budget pre-filtering.
 *
 * v1.5.4 — Fixed token economics and first-day cap:
 * - Token estimation counts actual formatted output with conservative divisor
 * - Prompt template and query overhead reserved from budget
 * - Newest day is capped if it exceeds budget alone
 * - 60-day cleanup at PiP startup only (no post-query deletes)
 */
class NotificationRepository(context: Context) {

    companion object {
        private const val TAG = "NotificationRepo"
        private const val SAFE_TOKEN_BUDGET = 1800.0
        // Conservative divisor for short heterogeneous text (notifications, URLs, emojis, app names)
        // SentencePiece/BPE tokenizers average ~2.5 chars/token for mixed-content short strings
        private const val TOKEN_DIVISOR = 2.5
        private const val DAYS_60_MILLIS = 60L * 24 * 60 * 60 * 1000

        // Overhead tokens reserved for prompt template and user query
        private const val PROMPT_TEMPLATE_OVERHEAD = 120.0
        private const val QUERY_OVERHEAD_RESERVE = 100.0
        private val EFFECTIVE_BUDGET = SAFE_TOKEN_BUDGET - PROMPT_TEMPLATE_OVERHEAD - QUERY_OVERHEAD_RESERVE
    }

    private val database = NotificationDatabase(context)

    /**
     * Returns the total number of notifications in the database,
     * optionally excluding apps in [excludedAppLabels].
     */
    fun getTotalCount(excludedAppLabels: List<String> = emptyList()): Int {
        val db = database.readableDatabase
        val query = if (excludedAppLabels.isNotEmpty()) {
            val placeholders = excludedAppLabels.joinToString(",") { "?" }
            "SELECT COUNT(*) FROM ${NotificationDatabase.TABLE_NOTIFICATIONS} WHERE ${NotificationDatabase.COL_APP_LABEL} NOT IN ($placeholders)"
        } else {
            "SELECT COUNT(*) FROM ${NotificationDatabase.TABLE_NOTIFICATIONS}"
        }
        db.rawQuery(query, if (excludedAppLabels.isNotEmpty()) excludedAppLabels.toTypedArray() else null).use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getInt(0)
            }
        }
        return 0
    }

    /**
     * Returns all distinct app labels present in the database, sorted A-Z.
     */
    fun getDistinctAppLabels(): List<String> {
        val db = database.readableDatabase
        val labels = mutableListOf<String>()
        db.rawQuery(
            "SELECT DISTINCT ${NotificationDatabase.COL_APP_LABEL} FROM ${NotificationDatabase.TABLE_NOTIFICATIONS} ORDER BY ${NotificationDatabase.COL_APP_LABEL} ASC",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                cursor.getString(0)?.let { if (it.isNotBlank()) labels.add(it) }
            }
        }
        return labels
    }

    /**
     * Loads ALL notifications from the database, newest first,
     * optionally excluding apps in [excludedAppLabels].
     */
    suspend fun getAllNotifications(excludedAppLabels: List<String> = emptyList()): List<NotificationEntry> = withContext(Dispatchers.IO) {
        val db = database.readableDatabase
        val entries = mutableListOf<NotificationEntry>()
        val query = if (excludedAppLabels.isNotEmpty()) {
            val placeholders = excludedAppLabels.joinToString(",") { "?" }
            "SELECT * FROM ${NotificationDatabase.TABLE_NOTIFICATIONS} WHERE ${NotificationDatabase.COL_APP_LABEL} NOT IN ($placeholders) ORDER BY ${NotificationDatabase.COL_TIMESTAMP} DESC"
        } else {
            "SELECT * FROM ${NotificationDatabase.TABLE_NOTIFICATIONS} ORDER BY ${NotificationDatabase.COL_TIMESTAMP} DESC"
        }
        db.rawQuery(query, if (excludedAppLabels.isNotEmpty()) excludedAppLabels.toTypedArray() else null).use { cursor ->
            while (cursor.moveToNext()) {
                entries.add(cursor.toEntry())
            }
        }
        entries
    }

    /**
     * Search notifications by keywords. For each keyword, matches title, body, or app_label.
     * Results are ordered newest-first (timestamp DESC).
     *
     * @param keywords List of keywords (must be non-empty; empty list returns empty list)
     * @param excludedAppLabels Apps to exclude from search (empty = no exclusion)
     * @return Matching notification entries
     */
    suspend fun searchByKeywords(keywords: List<String>, excludedAppLabels: List<String> = emptyList()): List<NotificationEntry> = withContext(Dispatchers.IO) {
        if (keywords.isEmpty()) {
            return@withContext emptyList<NotificationEntry>()
        }

        val db = database.readableDatabase
        val args = mutableListOf<String>()
        val keywordConditions = keywords.map { keyword ->
            args.add("%$keyword%")
            args.add("%$keyword%")
            args.add("%$keyword%")
            "(${NotificationDatabase.COL_TITLE} LIKE ? OR ${NotificationDatabase.COL_BODY} LIKE ? OR ${NotificationDatabase.COL_APP_LABEL} LIKE ?)"
        }

        val keywordWhere = keywordConditions.joinToString(" OR ")

        val query = if (excludedAppLabels.isNotEmpty()) {
            excludedAppLabels.forEach { args.add(it) }
            val appPlaceholders = excludedAppLabels.joinToString(",") { "?" }
            "SELECT * FROM ${NotificationDatabase.TABLE_NOTIFICATIONS} WHERE ($keywordWhere) AND ${NotificationDatabase.COL_APP_LABEL} NOT IN ($appPlaceholders) ORDER BY ${NotificationDatabase.COL_TIMESTAMP} DESC"
        } else {
            "SELECT * FROM ${NotificationDatabase.TABLE_NOTIFICATIONS} WHERE $keywordWhere ORDER BY ${NotificationDatabase.COL_TIMESTAMP} DESC"
        }

        val entries = mutableListOf<NotificationEntry>()
        db.rawQuery(query, args.toTypedArray()).use { cursor ->
            while (cursor.moveToNext()) {
                entries.add(cursor.toEntry())
            }
        }
        entries
    }

    /**
     * Delete notifications older than 60 days.
     *
     * @return Number of rows deleted
     */
    fun cleanupOldNotifications(): Int {
        val cutoff = System.currentTimeMillis() - DAYS_60_MILLIS
        val db = database.writableDatabase
        val deleted = db.delete(
            NotificationDatabase.TABLE_NOTIFICATIONS,
            "${NotificationDatabase.COL_TIMESTAMP} < ?",
            arrayOf(cutoff.toString())
        )
        Log.i(TAG, "cleanupOldNotifications deleted $deleted rows older than $cutoff")
        return deleted
    }

    /**
     * Estimate the token cost of a single notification entry as it will appear
     * in the LLM prompt (formatted line length / conservative divisor).
     */
    private fun estimateEntryTokens(entry: NotificationEntry): Double {
        val content = when {
            entry.title.isNotBlank() && entry.body.isNotBlank() -> "${entry.title}: ${entry.body}"
            entry.title.isNotBlank() -> entry.title
            entry.body.isNotBlank() -> entry.body
            else -> "(no content)"
        }
        // Match the exact format produced by buildHistoryText
        // "yyyy-MM-dd HH:mm | AppLabel | Content\n" = 16 + 3 + appLabel + 3 + content + 1
        val formattedLength = 23 + entry.appLabel.length + content.length
        return formattedLength / TOKEN_DIVISOR
    }

    /**
     * Apply the day-boundary greedy token budget filter on an arbitrary list of entries.
     *
     * Algorithm:
     * 1. Group by calendar day (LocalDate from system default zone)
     * 2. Iterate days newest-first
     * 3. The newest day is capped: only its newest entries that fit are included
     * 4. Subsequent complete days are included atomically until budget exceeded
     *
     * @param entries List of notifications to filter (assumed newest-first)
     * @return FilterResult with analyzed entries, cutoff date, and counts
     */
    fun applyDayBoundaryFilter(entries: List<NotificationEntry>): DayBoundaryFilterResult {
        if (entries.isEmpty()) {
            return DayBoundaryFilterResult(emptyList(), null, 0, 0)
        }

        val totalMatches = entries.size

        // Group by calendar day, newest first
        val days = entries.groupBy { entry ->
            Instant.ofEpochMilli(entry.timestamp)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
        }.toSortedMap(reverseOrder())

        var runningTotal = 0.0
        val includedDates = mutableListOf<LocalDate>()
        val includedEntries = mutableListOf<NotificationEntry>()

        for ((date, dayEntries) in days) {
            val sortedDay = dayEntries.sortedByDescending { it.timestamp }

            if (includedDates.isEmpty()) {
                // First (newest) day: cap to budget, include newest entries that fit
                var dayRunningTotal = 0.0
                val includedFromDay = mutableListOf<NotificationEntry>()
                for (entry in sortedDay) {
                    val entryTokens = estimateEntryTokens(entry)
                    if (dayRunningTotal + entryTokens > EFFECTIVE_BUDGET) {
                        break
                    }
                    dayRunningTotal += entryTokens
                    includedFromDay.add(entry)
                }
                if (includedFromDay.isNotEmpty()) {
                    includedDates.add(date)
                    runningTotal += dayRunningTotal
                    includedEntries.addAll(includedFromDay)
                }
            } else {
                // Subsequent days: include whole day atomically or stop
                val dayTokens = sortedDay.sumOf { estimateEntryTokens(it) }
                if (runningTotal + dayTokens > EFFECTIVE_BUDGET) {
                    break
                }
                includedDates.add(date)
                runningTotal += dayTokens
                includedEntries.addAll(sortedDay)
            }
        }

        val cutoffDate = includedDates.lastOrNull()
        val analyzedCount = includedEntries.size

        return DayBoundaryFilterResult(
            analyzedEntries = includedEntries,
            cutoffDate = cutoffDate,
            totalMatches = totalMatches,
            analyzedCount = analyzedCount
        )
    }

    /**
     * Build the history text for the LLM prompt from a list of entries.
     * Format: yyyy-MM-dd HH:mm | AppLabel | Title: Body
     */
    fun buildHistoryText(entries: List<NotificationEntry>): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val sb = StringBuilder()
        entries.forEach { entry ->
            val content = when {
                entry.title.isNotBlank() && entry.body.isNotBlank() -> "${entry.title}: ${entry.body}"
                entry.title.isNotBlank() -> entry.title
                entry.body.isNotBlank() -> entry.body
                else -> "(no content)"
            }
            sb.append("${sdf.format(Date(entry.timestamp))} | ${entry.appLabel} | $content\n")
        }
        return sb.toString().trimEnd()
    }

    fun close() {
        database.close()
    }

    private fun Cursor.toEntry(): NotificationEntry {
        return NotificationEntry(
            id = getLong(getColumnIndexOrThrow(NotificationDatabase.COL_ID)),
            timestamp = getLong(getColumnIndexOrThrow(NotificationDatabase.COL_TIMESTAMP)),
            packageName = getString(getColumnIndexOrThrow(NotificationDatabase.COL_PACKAGE_NAME)) ?: "",
            appLabel = getString(getColumnIndexOrThrow(NotificationDatabase.COL_APP_LABEL)) ?: "",
            title = getString(getColumnIndexOrThrow(NotificationDatabase.COL_TITLE)) ?: "",
            body = getString(getColumnIndexOrThrow(NotificationDatabase.COL_BODY)) ?: "",
            category = getString(getColumnIndexOrThrow(NotificationDatabase.COL_CATEGORY)) ?: "",
            priority = getInt(getColumnIndexOrThrow(NotificationDatabase.COL_PRIORITY)),
            isGroupSummary = getInt(getColumnIndexOrThrow(NotificationDatabase.COL_IS_GROUP_SUMMARY)),
            dayOfWeek = getInt(getColumnIndexOrThrow(NotificationDatabase.COL_DAY_OF_WEEK)),
            hourOfDay = getInt(getColumnIndexOrThrow(NotificationDatabase.COL_HOUR_OF_DAY))
        )
    }
}
