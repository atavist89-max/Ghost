package com.ghost.app.notification

import android.content.Context

/**
 * SharedPreferences wrapper for Notification Historian user settings.
 */
object NotificationPrefs {

    private const val PREFS_NAME = "ghost_notification_prefs"
    private const val KEY_EXCLUDED_APPS = "excluded_apps"

    fun saveExcludedApps(context: Context, apps: Set<String>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_EXCLUDED_APPS, apps)
            .apply()
    }

    fun loadExcludedApps(context: Context): Set<String> {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY_EXCLUDED_APPS, emptySet()) ?: emptySet()
    }
}
