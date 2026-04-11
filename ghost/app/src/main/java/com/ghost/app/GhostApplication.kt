package com.ghost.app

import android.app.Application
import android.content.Context
import android.util.Log
import com.ghost.app.utils.MemoryManager

/**
 * Application class for Ghost.
 * Handles global initialization and memory management.
 */
class GhostApplication : Application() {

    companion object {
        private const val TAG = "GhostApp"
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Ghost Application starting")
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Log.d(TAG, "onTrimMemory level: $level")
        MemoryManager.trimMemory(this, level)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Log.w(TAG, "onLowMemory called")
        MemoryManager.releaseAll()
    }
}
