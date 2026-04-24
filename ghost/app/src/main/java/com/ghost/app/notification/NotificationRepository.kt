package com.ghost.app.notification

import android.content.Context
import android.database.Cursor
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject
import org.json.JSONException

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

data class StructuredFilter(
    val targetApps: List<String> = emptyList(),
    val personKeywords: List<String> = emptyList(),
    val topicKeywords: List<String> = emptyList(),
    val timeScopeDays: Int? = null,
    val confidence: String = "low",
    val strategy: String = "broad"
)

data class TokenAccumulatorResult(
    val analyzedEntries: List<NotificationEntry>,
    val totalMatches: Int,
    val analyzedCount: Int,
    val oldestIncludedTimestamp: Long?,
    val isEntryTooLarge: Boolean,
    val structuredFilter: StructuredFilter,
    val escalationStep: Int
)
class NotificationRepository(context: Context) {

    companion object {
        private const val TAG = "NotificationRepo"
        private const val SAFE_TOKEN_BUDGET = 1800.0
        private const val TOKEN_DIVISOR = 2.5
        private const val DAYS_60_MILLIS = 60L * 24 * 60 * 60 * 1000
        private const val PROMPT_TEMPLATE_OVERHEAD = 120.0
        private const val QUERY_OVERHEAD_RESERVE = 100.0
        private val EFFECTIVE_BUDGET = SAFE_TOKEN_BUDGET - PROMPT_TEMPLATE_OVERHEAD - QUERY_OVERHEAD_RESERVE
    }

    private val database = NotificationDatabase(context)

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

    fun cleanupDuplicateNotifications(): Int {
        val db = database.writableDatabase
        val idsToDelete = mutableListOf<Long>()
        db.rawQuery(
            "SELECT ${NotificationDatabase.COL_ID} FROM ${NotificationDatabase.TABLE_NOTIFICATIONS} WHERE ${NotificationDatabase.COL_ID} NOT IN (" +
            "SELECT MIN(${NotificationDatabase.COL_ID}) FROM ${NotificationDatabase.TABLE_NOTIFICATIONS} " +
            "GROUP BY datetime(${NotificationDatabase.COL_TIMESTAMP}/1000, 'unixepoch'), ${NotificationDatabase.COL_TITLE}, ${NotificationDatabase.COL_BODY})",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                idsToDelete.add(cursor.getLong(0))
            }
        }
        if (idsToDelete.isEmpty()) return 0
        val placeholders = idsToDelete.joinToString(",") { "?" }
        val deleted = db.delete(
            NotificationDatabase.TABLE_NOTIFICATIONS,
            "${NotificationDatabase.COL_ID} IN ($placeholders)",
            idsToDelete.map { it.toString() }.toTypedArray()
        )
        Log.i(TAG, "cleanupDuplicateNotifications deleted $deleted rows")
        return deleted
    }

    private fun estimateEntryTokens(entry: NotificationEntry): Double {
        val content = when {
            entry.title.isNotBlank() && entry.body.isNotBlank() -> "${entry.title}: ${entry.body}"
            entry.title.isNotBlank() -> entry.title
            entry.body.isNotBlank() -> entry.body
            else -> "(no content)"
        }
        val formattedLength = 23 + entry.appLabel.length + content.length
        return formattedLength / TOKEN_DIVISOR
    }

    fun extractIntent(query: String, appLabels: List<String>, quickInfer: (String) -> String): StructuredFilter {
        val appListBlock = if (appLabels.isEmpty()) {
            "No apps are currently logged in the database."
        } else {
            "Distinct app labels in database: ${appLabels.joinToString(", ")}"
        }

        val prompt = """
            You are a strict intent extraction engine. Given a user's question about their notification history, output ONLY a valid JSON object with the exact fields below.

            Database schema:
            Table: notifications
            Columns: id, timestamp, package_name, app_label, title, body, category, priority, is_group_summary, day_of_week, hour_of_day

            $appListBlock

            Extract these fields:
            - target_apps: list of app labels mentioned or implied. Empty means all apps.
            - person_keywords: list of names, handles, or identifiers mentioned.
            - topic_keywords: list of subject matter terms and semantic synonyms.
            - time_scope_days: integer days back from now, or null if no time constraint.
            - confidence: "high", "medium", or "low" — your certainty about extraction accuracy.
            - strategy: "precise", "fuzzy", or "broad" — recommended search aggressiveness.

            Examples:
            User: "Did John ever ask me to buy onions?"
            Output: {"target_apps":[],"person_keywords":["John"],"topic_keywords":["onions","buy"],"time_scope_days":null,"confidence":"high","strategy":"precise"}

            User: "Anything important from Slack today?"
            Output: {"target_apps":["Slack"],"person_keywords":[],"topic_keywords":["important"],"time_scope_days":1,"confidence":"low","strategy":"broad"}

            CRITICAL: Output ONLY the JSON object. No markdown, no prose, no reasoning, no code fences.

            User: "$query"
            Output:
        """.trimIndent()

        return try {
            val raw = quickInfer(prompt)
            val json = JSONObject(raw.trim())
            StructuredFilter(
                targetApps = json.optJSONArray("target_apps")?.let { arr ->
                    List(arr.length()) { i -> arr.optString(i, "").trim() }.filter { it.isNotBlank() }
                } ?: emptyList(),
                personKeywords = json.optJSONArray("person_keywords")?.let { arr ->
                    List(arr.length()) { i -> arr.optString(i, "").trim() }.filter { it.isNotBlank() }
                } ?: emptyList(),
                topicKeywords = json.optJSONArray("topic_keywords")?.let { arr ->
                    List(arr.length()) { i -> arr.optString(i, "").trim() }.filter { it.isNotBlank() }
                } ?: emptyList(),
                timeScopeDays = json.opt("time_scope_days")?.let { if (it == JSONObject.NULL) null else (it as? Number)?.toInt() },
                confidence = when (json.optString("confidence", "low").lowercase()) {
                    "high" -> "high"
                    "medium" -> "medium"
                    else -> "low"
                },
                strategy = when (json.optString("strategy", "broad").lowercase()) {
                    "precise" -> "precise"
                    "fuzzy" -> "fuzzy"
                    else -> "broad"
                }
            )
        } catch (e: JSONException) {
            Log.w(TAG, "Intent extraction JSON parse failed, using broad fallback", e)
            StructuredFilter(strategy = "broad", confidence = "low")
        } catch (e: Exception) {
            Log.w(TAG, "Intent extraction failed, using broad fallback", e)
            StructuredFilter(strategy = "broad", confidence = "low")
        }
    }

    fun buildSqlQuery(filter: StructuredFilter, excludedApps: List<String>): Pair<String, List<String>> {
        val conditions = mutableListOf<String>()
        val args = mutableListOf<String>()

        if (excludedApps.isNotEmpty()) {
            val placeholders = excludedApps.joinToString(",") { "?" }
            conditions.add("${NotificationDatabase.COL_APP_LABEL} NOT IN ($placeholders)")
            args.addAll(excludedApps)
        }

        val effectiveTargetApps = filter.targetApps.filter { it !in excludedApps }
        if (effectiveTargetApps.isNotEmpty()) {
            val placeholders = effectiveTargetApps.joinToString(",") { "?" }
            conditions.add("${NotificationDatabase.COL_APP_LABEL} IN ($placeholders)")
            args.addAll(effectiveTargetApps)
        }

        filter.timeScopeDays?.let { days ->
            if (days > 0) {
                val cutoff = System.currentTimeMillis() - (days * 86400000L)
                conditions.add("${NotificationDatabase.COL_TIMESTAMP} > ?")
                args.add(cutoff.toString())
            }
        }

        val confidence = filter.confidence.lowercase()
        val strategy = filter.strategy.lowercase()
        val allKeywords = filter.personKeywords + filter.topicKeywords

        when {
            confidence == "low" || strategy == "broad" -> {
                // Drop all keyword filters
            }
            confidence == "high" && strategy == "precise" -> {
                val personClauses = mutableListOf<String>()
                val topicClauses = mutableListOf<String>()
                filter.personKeywords.forEach { kw ->
                    personClauses.add("${NotificationDatabase.COL_TITLE} LIKE ?")
                    personClauses.add("${NotificationDatabase.COL_BODY} LIKE ?")
                    args.add("%$kw%")
                    args.add("%$kw%")
                }
                filter.topicKeywords.forEach { kw ->
                    topicClauses.add("${NotificationDatabase.COL_TITLE} LIKE ?")
                    topicClauses.add("${NotificationDatabase.COL_BODY} LIKE ?")
                    args.add("%$kw%")
                    args.add("%$kw%")
                }
                val groupClauses = mutableListOf<String>()
                if (personClauses.isNotEmpty()) {
                    groupClauses.add("(${personClauses.joinToString(" OR ")})")
                }
                if (topicClauses.isNotEmpty()) {
                    groupClauses.add("(${topicClauses.joinToString(" OR ")})")
                }
                if (groupClauses.isNotEmpty()) {
                    conditions.add(groupClauses.joinToString(" AND "))
                }
            }
            confidence == "high" && strategy == "fuzzy" -> {
                val personClauses = mutableListOf<String>()
                val topicClauses = mutableListOf<String>()
                filter.personKeywords.forEach { kw ->
                    personClauses.add("${NotificationDatabase.COL_TITLE} LIKE ?")
                    personClauses.add("${NotificationDatabase.COL_BODY} LIKE ?")
                    args.add("%$kw%")
                    args.add("%$kw%")
                }
                filter.topicKeywords.forEach { kw ->
                    topicClauses.add("${NotificationDatabase.COL_TITLE} LIKE ?")
                    topicClauses.add("${NotificationDatabase.COL_BODY} LIKE ?")
                    args.add("%$kw%")
                    args.add("%$kw%")
                }
                val groupClauses = mutableListOf<String>()
                if (personClauses.isNotEmpty()) {
                    groupClauses.add("(${personClauses.joinToString(" OR ")})")
                }
                if (topicClauses.isNotEmpty()) {
                    groupClauses.add("(${topicClauses.joinToString(" OR ")})")
                }
                if (groupClauses.isNotEmpty()) {
                    conditions.add(groupClauses.joinToString(" AND "))
                }
            }
            (confidence == "medium" && strategy == "precise") ||
            (confidence == "medium" && strategy == "fuzzy") -> {
                if (allKeywords.isNotEmpty()) {
                    val clauses = allKeywords.flatMap { kw ->
                        args.add("%$kw%")
                        args.add("%$kw%")
                        listOf(
                            "${NotificationDatabase.COL_TITLE} LIKE ?",
                            "${NotificationDatabase.COL_BODY} LIKE ?"
                        )
                    }
                    conditions.add("(${clauses.joinToString(" OR ")})")
                }
            }
            else -> {
                // Drop all keyword filters for any other combination
            }
        }

        val whereClause = if (conditions.isNotEmpty()) "WHERE ${conditions.joinToString(" AND ")}" else ""
        val sql = "SELECT * FROM ${NotificationDatabase.TABLE_NOTIFICATIONS} $whereClause ORDER BY ${NotificationDatabase.COL_TIMESTAMP} DESC LIMIT 300"
        return sql to args
    }

    private fun executeFilterQuery(sql: String, args: List<String>): List<NotificationEntry> {
        val db = database.readableDatabase
        val entries = mutableListOf<NotificationEntry>()
        db.rawQuery(sql, args.toTypedArray()).use { cursor ->
            while (cursor.moveToNext()) {
                entries.add(cursor.toEntry())
            }
        }
        return entries
    }

    fun tokenGreedyAccumulator(entries: List<NotificationEntry>, filter: StructuredFilter): TokenAccumulatorResult {
        if (entries.isEmpty()) {
            return TokenAccumulatorResult(
                analyzedEntries = emptyList(),
                totalMatches = 0,
                analyzedCount = 0,
                oldestIncludedTimestamp = null,
                isEntryTooLarge = false,
                structuredFilter = filter,
                escalationStep = 0
            )
        }

        var runningTotal = 0.0
        val included = mutableListOf<NotificationEntry>()
        var oldestTimestamp: Long? = null

        for (entry in entries) {
            val entryTokens = estimateEntryTokens(entry)
            if (runningTotal + entryTokens > EFFECTIVE_BUDGET) {
                break
            }
            runningTotal += entryTokens
            included.add(entry)
            oldestTimestamp = entry.timestamp
        }

        val isEntryTooLarge = included.isEmpty() && entries.isNotEmpty()

        return TokenAccumulatorResult(
            analyzedEntries = included,
            totalMatches = entries.size,
            analyzedCount = included.size,
            oldestIncludedTimestamp = oldestTimestamp,
            isEntryTooLarge = isEntryTooLarge,
            structuredFilter = filter,
            escalationStep = 0
        )
    }

    fun runNotificationPipeline(
        userQuery: String,
        excludedApps: List<String>,
        appLabels: List<String>,
        quickInfer: (String) -> String,
        onEscalation: (String) -> Unit
    ): TokenAccumulatorResult {
        val baseFilter = if (userQuery.isBlank()) {
            StructuredFilter(strategy = "broad", confidence = "low")
        } else {
            extractIntent(userQuery, appLabels, quickInfer)
        }

        val (sql0, args0) = buildSqlQuery(baseFilter, excludedApps)
        val entries0 = executeFilterQuery(sql0, args0)
        val result0 = tokenGreedyAccumulator(entries0, baseFilter)
        if (result0.totalMatches > 0) {
            return result0.copy(escalationStep = 0)
        }

        onEscalation("Widening search: dropping topic keywords...")
        val filter1 = baseFilter.copy(topicKeywords = emptyList())
        val (sql1, args1) = buildSqlQuery(filter1, excludedApps)
        val entries1 = executeFilterQuery(sql1, args1)
        val result1 = tokenGreedyAccumulator(entries1, filter1)
        if (result1.totalMatches > 0) {
            return result1.copy(escalationStep = 1)
        }

        onEscalation("Widening search: dropping person keywords...")
        val filter2 = filter1.copy(personKeywords = emptyList())
        val (sql2, args2) = buildSqlQuery(filter2, excludedApps)
        val entries2 = executeFilterQuery(sql2, args2)
        val result2 = tokenGreedyAccumulator(entries2, filter2)
        if (result2.totalMatches > 0) {
            return result2.copy(escalationStep = 2)
        }

        onEscalation("Widening search: searching all apps...")
        val filter3 = filter2.copy(targetApps = emptyList())
        val (sql3, args3) = buildSqlQuery(filter3, excludedApps)
        val entries3 = executeFilterQuery(sql3, args3)
        val result3 = tokenGreedyAccumulator(entries3, filter3)
        if (result3.totalMatches > 0) {
            return result3.copy(escalationStep = 3)
        }

        onEscalation("Widening search: searching all time...")
        val filter4 = filter3.copy(timeScopeDays = null)
        val (sql4, args4) = buildSqlQuery(filter4, excludedApps)
        val entries4 = executeFilterQuery(sql4, args4)
        val result4 = tokenGreedyAccumulator(entries4, filter4)
        if (result4.totalMatches > 0) {
            return result4.copy(escalationStep = 4)
        }

        val fallbackSql = if (excludedApps.isNotEmpty()) {
            val placeholders = excludedApps.joinToString(",") { "?" }
            "SELECT * FROM ${NotificationDatabase.TABLE_NOTIFICATIONS} WHERE ${NotificationDatabase.COL_APP_LABEL} NOT IN ($placeholders) ORDER BY ${NotificationDatabase.COL_TIMESTAMP} DESC LIMIT 300"
        } else {
            "SELECT * FROM ${NotificationDatabase.TABLE_NOTIFICATIONS} ORDER BY ${NotificationDatabase.COL_TIMESTAMP} DESC LIMIT 300"
        }
        val fallbackArgs = excludedApps.toList()
        val fallbackEntries = executeFilterQuery(fallbackSql, fallbackArgs)
        val fallbackResult = tokenGreedyAccumulator(fallbackEntries, StructuredFilter(strategy = "broad", confidence = "low"))
        if (fallbackResult.totalMatches > 0) {
            return fallbackResult.copy(escalationStep = 5)
        }

        return TokenAccumulatorResult(
            analyzedEntries = emptyList(),
            totalMatches = 0,
            analyzedCount = 0,
            oldestIncludedTimestamp = null,
            isEntryTooLarge = false,
            structuredFilter = baseFilter,
            escalationStep = 5
        )
    }

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
