package com.ghost.app.notification

import android.content.Context
import android.database.Cursor
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

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
 * Repository for querying notification history with token-budget pre-filtering.
 *
 * Phase C+D — Bell Press (MERGED):
 * - Queries all notifications from SQLite
 * - Groups by calendar day (full day, 00:00:00 boundary)
 * - Iterates days newest-first, accumulating token estimates
 * - Stops when accumulated + next_day_tokens > 1,600 (safe budget)
 * - Deletes everything older than the start of the cutoff day
 * - Returns the pre-filtered history text and the "Oldest from:" label
 */
class NotificationRepository(context: Context) {

    companion object {
        private const val TAG = "NotificationRepo"
        private const val SAFE_TOKEN_BUDGET = 1600.0
        private const val TOKEN_DIVISOR = 3.5
    }

    private val database = NotificationDatabase(context)

    /**
     * Loads notification history pre-filtered by the day-boundary greedy token budget algorithm.
     *
     * @return Pair of (history text, cutoff label). If the database is empty,
     *         returns ("", "No notifications logged").
     */
    suspend fun loadPrefiltered(): Pair<String, String> = withContext(Dispatchers.IO) {
        val db = database.readableDatabase

        val entries = mutableListOf<NotificationEntry>()
        db.rawQuery(
            "SELECT * FROM ${NotificationDatabase.TABLE_NOTIFICATIONS} ORDER BY ${NotificationDatabase.COL_TIMESTAMP} DESC",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                entries.add(cursor.toEntry())
            }
        }

        if (entries.isEmpty()) {
            return@withContext Pair("", "No notifications logged")
        }

        // Group by calendar day (full day, 00:00:00 boundary) in Kotlin
        val days = entries.groupBy { entry ->
            val cal = Calendar.getInstance(TimeZone.getDefault()).apply {
                timeInMillis = entry.timestamp
            }
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            cal.timeInMillis
        }.toSortedMap(reverseOrder()) // Newest day first

        var accumulatedTokens = 0.0
        val includedDays = mutableListOf<Long>() // day start epoch millis, newest first

        for ((dayStart, dayEntries) in days) {
            val dayTokens = dayEntries.sumOf { entry ->
                (entry.appLabel.length + entry.title.length + entry.body.length) / TOKEN_DIVISOR
            }

            if (includedDays.isEmpty()) {
                // Always include the newest day, even if it exceeds budget on its own.
                includedDays.add(dayStart)
                accumulatedTokens += dayTokens
            } else {
                if (accumulatedTokens + dayTokens > SAFE_TOKEN_BUDGET) {
                    break
                }
                includedDays.add(dayStart)
                accumulatedTokens += dayTokens
            }
        }

        // The last included day is the cutoff day.
        val cutoffDay = includedDays.last()

        // DELETE from SQLite everything older than the START of that cutoff day.
        val deletedRows = db.delete(
            NotificationDatabase.TABLE_NOTIFICATIONS,
            "${NotificationDatabase.COL_TIMESTAMP} < ?",
            arrayOf(cutoffDay.toString())
        )
        Log.i(TAG, "Deleted $deletedRows rows older than cutoff day $cutoffDay")

        // Build the history text from included days (newest-first).
        val historyBuilder = StringBuilder()
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

        includedDays.sortedDescending().forEach { dayStart ->
            val dayEntries = days[dayStart] ?: return@forEach
            dayEntries.forEach { entry ->
                historyBuilder.append("[${sdf.format(Date(entry.timestamp))}] ")
                historyBuilder.append("${entry.appLabel} | ${entry.title} | ${entry.body}\n")
            }
        }

        val dateLabel = SimpleDateFormat("MMM d, yyyy", Locale.US).format(Date(cutoffDay))
        Pair(historyBuilder.toString().trimEnd(), "Oldest from: $dateLabel")
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
