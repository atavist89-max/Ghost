package com.ghost.app.utils

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-memory debug logger for capturing app logs.
 * Displays logs in the UI for debugging without ADB.
 */
object DebugLogger {
    
    private const val MAX_LOGS = 500
    private val logs = CopyOnWriteArrayList<LogEntry>()
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    
    private val listeners = mutableListOf<() -> Unit>()
    
    data class LogEntry(
        val timestamp: Long,
        val level: String,
        val tag: String,
        val message: String
    ) {
        fun format(): String {
            val time = dateFormat.format(Date(timestamp))
            return "$time [$level] $tag: $message"
        }
    }
    
    enum class Level {
        VERBOSE, DEBUG, INFO, WARN, ERROR
    }
    
    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }
    
    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }
    
    private fun notifyListeners() {
        listeners.forEach { it.invoke() }
    }
    
    fun v(tag: String, message: String) {
        log(Level.VERBOSE, tag, message)
    }
    
    fun d(tag: String, message: String) {
        log(Level.DEBUG, tag, message)
        Log.d(tag, message)
    }
    
    fun i(tag: String, message: String) {
        log(Level.INFO, tag, message)
        Log.i(tag, message)
    }
    
    fun w(tag: String, message: String) {
        log(Level.WARN, tag, message)
        Log.w(tag, message)
    }
    
    fun e(tag: String, message: String) {
        log(Level.ERROR, tag, message)
        Log.e(tag, message)
    }
    
    fun e(tag: String, message: String, throwable: Throwable) {
        val fullMessage = "$message\n${throwable.stackTraceToString()}"
        log(Level.ERROR, tag, fullMessage)
        Log.e(tag, message, throwable)
    }
    
    private fun log(level: Level, tag: String, message: String) {
        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            level = level.name.first().toString(),
            tag = tag,
            message = message
        )
        
        logs.add(entry)
        
        // Trim old logs
        while (logs.size > MAX_LOGS) {
            logs.removeAt(0)
        }
        
        notifyListeners()
    }
    
    fun getLogs(): List<LogEntry> {
        return logs.toList()
    }
    
    fun getLogsText(): String {
        return logs.joinToString("\n") { it.format() }
    }
    
    fun clear() {
        logs.clear()
        notifyListeners()
    }
    
    fun getLogsForTag(tag: String): List<LogEntry> {
        return logs.filter { it.tag.contains(tag, ignoreCase = true) }
    }
}
