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
import java.time.format.DateTimeFormatter
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
 * v1.5 — Notification Historian:
 * - Keyword-based SQLite search (zero LLM)
 * - Day-boundary greedy filter operates on matched results, not full table
 * - 60-day cleanup (triggered by PiP startup only)
 * - Post-query destructive delete after successful LLM response
 * - Per-app exclusion filter persisted across sessions
 */
class NotificationRepository(context: Context) {

    companion object {
        private const val TAG = "NotificationRepo"
        private const val SAFE_TOKEN_BUDGET = 1600.0
        private const val TOKEN_DIVISOR = 3.5
        private const val DAYS_60_MILLIS = 60L * 24 * 60 * 60 * 1000
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
     * Apply the day-boundary greedy token budget filter on an arbitrary list of entries.
     *
     * Algorithm:
     * 1. Group by calendar day (LocalDate from system default zone)
     * 2. Iterate days newest-first
     * 3. Accumulate token estimates until budget exceeded
     * 4. Always include the newest day even if it exceeds budget alone
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
            val dayTokens = dayEntries.sumOf { entry ->
                (entry.appLabel.length + entry.title.length + entry.body.length) / TOKEN_DIVISOR
            }

            if (includedDates.isEmpty()) {
                // Always include the newest day, even if it exceeds budget on its own
                includedDates.add(date)
                runningTotal += dayTokens
                includedEntries.addAll(dayEntries.sortedByDescending { it.timestamp })
            } else {
                if (runningTotal + dayTokens > SAFE_TOKEN_BUDGET) {
                    break
                }
                includedDates.add(date)
                runningTotal += dayTokens
                includedEntries.addAll(dayEntries.sortedByDescending { it.timestamp })
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

    /**
     * Delete all notifications older than the given timestamp.
     *
     * @return Number of rows deleted
     */
    fun deleteOlderThan(timestampMillis: Long): Int {
        val db = database.writableDatabase
        val deleted = db.delete(
            NotificationDatabase.TABLE_NOTIFICATIONS,
            "${NotificationDatabase.COL_TIMESTAMP} < ?",
            arrayOf(timestampMillis.toString())
        )
        Log.i(TAG, "deleteOlderThan deleted $deleted rows older than $timestampMillis")
        return deleted
    }

    /**
     * Legacy helper: loads all notifications, applies greedy filter, AND deletes old rows.
     * Kept for any direct callers; prefer the new granular methods.
     */
    suspend fun loadPrefiltered(): Pair<String, String> = withContext(Dispatchers.IO) {
        val all = getAllNotifications()
        if (all.isEmpty()) {
            return@withContext Pair("", "No notifications logged")
        }
        val result = applyDayBoundaryFilter(all)
        if (result.analyzedEntries.isEmpty()) {
            return@withContext Pair("", "No notifications logged")
        }
        val history = buildHistoryText(result.analyzedEntries)
        val cutoffMillis = result.cutoffDate!!.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        deleteOlderThan(cutoffMillis)
        val dateLabel = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US).format(result.cutoffDate)
        Pair(history, "Oldest from: $dateLabel")
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
