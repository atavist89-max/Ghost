package com.ghost.app.utils

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.util.Log
import java.lang.ref.WeakReference

/**
 * Memory management utilities for aggressive cleanup.
 * Ensures the app releases all memory when the PiP is closed.
 */
object MemoryManager {

    private const val TAG = "GhostMemory"

    // Registry of objects that need cleanup
    private val cleanupCallbacks = mutableListOf<WeakReference<() -> Unit>>()

    /**
     * Register a callback to be called during memory release.
     * @param callback Function to call for cleanup
     */
    fun registerCleanup(callback: () -> Unit) {
        cleanupCallbacks.add(WeakReference(callback))
    }

    /**
     * Unregister a previously registered cleanup callback.
     */
    fun unregisterCleanup(callback: () -> Unit) {
        cleanupCallbacks.removeAll { it.get() == callback }
    }

    /**
     * Release all registered resources and trigger garbage collection.
     * Call this when the PiP window is closed.
     */
    fun releaseAll() {
        Log.d(TAG, "Starting aggressive memory cleanup")

        // Execute all registered cleanup callbacks
        cleanupCallbacks.forEach { ref ->
            try {
                ref.get()?.invoke()
            } catch (e: Exception) {
                Log.e(TAG, "Cleanup callback failed", e)
            }
        }

        // Clear all weak references
        cleanupCallbacks.clear()

        // Suggest garbage collection
        System.gc()

        // Request finalization
        System.runFinalization()

        // Second GC pass for finalized objects
        System.gc()

        Log.d(TAG, "Memory cleanup completed")
    }

    /**
     * Trim memory at the application level.
     * @param context Application context
     * @param level Trim memory level from onTrimMemory callback
     */
    fun trimMemory(context: Context, level: Int) {
        when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                Log.w(TAG, "Critical memory pressure detected")
                releaseAll()
            }
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_MODERATE -> {
                Log.i(TAG, "Memory pressure detected, trimming")
                System.gc()
            }
        }
    }

    /**
     * Log current memory usage for debugging.
     */
    fun logMemoryStats(context: Context) {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)

        val runtime = Runtime.getRuntime()
        val usedMem = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val maxMem = runtime.maxMemory() / (1024 * 1024)

        Log.d(TAG, "App Memory: ${usedMem}MB / ${maxMem}MB")
        Log.d(TAG, "System Memory: ${memoryInfo.availMem / (1024 * 1024)}MB available")
    }

    /**
     * Clear all bitmap-related memory caches if any exist.
     * This is a no-op placeholder for future caching implementations.
     */
    fun clearImageCaches() {
        // Placeholder for image cache cleanup
        // Currently we don't cache bitmaps, but this allows for future optimization
    }
}
