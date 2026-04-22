package com.ghost.app.notification

import android.app.Notification
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.ghost.app.utils.GhostPaths
import java.util.Calendar
import java.util.TimeZone

/**
 * Background notification logger service.
 * Runs 24/7 once the user grants Notification Access in system settings.
 * Captures every notification, flattens it to structured text, and writes to SQLite.
 * Does NOT load the LLM. Does NOT run inference. Dumb, lightweight text logger only.
 */
class NotificationLoggerService : NotificationListenerService() {

    companion object {
        private const val TAG = "NotificationLogger"

        /**
         * Heuristic app-to-category mapping. No semantic classification. No LLM.
         */
        private val APP_CATEGORY_MAP = mapOf(
            "com.whatsapp" to "social",
            "com.facebook.orca" to "social",
            "com.facebook.katana" to "social",
            "com.instagram.android" to "social",
            "com.twitter.android" to "social",
            "com.x.android" to "social",
            "com.snapchat.android" to "social",
            "com.linkedin.android" to "social",
            "com.discord" to "social",
            "com.slack" to "social",
            "com.telegram.messenger" to "social",
            "org.telegram.messenger" to "social",
            "com.google.android.gm" to "communication",
            "com.google.android.apps.messaging" to "communication",
            "com.android.messaging" to "communication",
            "com.samsung.android.messaging" to "communication",
            "com.google.android.apps.maps" to "navigation",
            "com.waze" to "navigation",
            "com.spotify.music" to "media",
            "com.google.android.youtube" to "media",
            "com.netflix.mediaclient" to "media",
            "com.google.android.apps.nbu.paisa.user" to "finance",
            "com.paypal.android.p2pmobile" to "finance",
            "com.amazon.mShop.android.shopping" to "shopping",
            "com.android.chrome" to "browser",
            "com.google.android.apps.docs" to "productivity",
            "com.microsoft.teams" to "productivity",
            "com.google.android.calendar" to "productivity",
            "com.android.calendar" to "productivity"
        )
    }

    private var database: NotificationDatabase? = null
    private var db: android.database.sqlite.SQLiteDatabase? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "NotificationLoggerService created")
        try {
            database = NotificationDatabase(this)
            db = database?.writableDatabase
            Log.i(TAG, "Database opened: ${GhostPaths.NOTIFICATION_DB}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open database", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        db?.close()
        database?.close()
        Log.i(TAG, "NotificationLoggerService destroyed")
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "Notification listener connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.i(TAG, "Notification listener disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val dbInstance = db ?: return

        try {
            val extras = sbn.notification?.extras ?: return
            val packageName = sbn.packageName ?: "unknown"

            val appLabel = try {
                packageManager.getApplicationLabel(
                    packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
                ).toString()
            } catch (e: Exception) {
                packageName
            }

            val title = extras.getString(Notification.EXTRA_TITLE)
                ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
                ?: ""
            val body = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
                ?: ""

            val category = sbn.notification?.category
                ?: APP_CATEGORY_MAP[packageName]
                ?: "unknown"
            val priority = sbn.notification?.priority ?: Notification.PRIORITY_DEFAULT
            val isGroupSummary = if (
                sbn.notification?.flags?.and(Notification.FLAG_GROUP_SUMMARY) != 0
            ) 1 else 0

            val cal = Calendar.getInstance(TimeZone.getDefault()).apply {
                timeInMillis = sbn.postTime
            }
            val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
            val hourOfDay = cal.get(Calendar.HOUR_OF_DAY)

            val values = android.content.ContentValues().apply {
                put(NotificationDatabase.COL_TIMESTAMP, sbn.postTime)
                put(NotificationDatabase.COL_PACKAGE_NAME, packageName)
                put(NotificationDatabase.COL_APP_LABEL, appLabel)
                put(NotificationDatabase.COL_TITLE, title)
                put(NotificationDatabase.COL_BODY, body)
                put(NotificationDatabase.COL_CATEGORY, category)
                put(NotificationDatabase.COL_PRIORITY, priority)
                put(NotificationDatabase.COL_IS_GROUP_SUMMARY, isGroupSummary)
                put(NotificationDatabase.COL_DAY_OF_WEEK, dayOfWeek)
                put(NotificationDatabase.COL_HOUR_OF_DAY, hourOfDay)
            }

            dbInstance.insert(NotificationDatabase.TABLE_NOTIFICATIONS, null, values)
            Log.d(TAG, "Logged notification from $packageName at ${sbn.postTime}")
        } catch (e: Exception) {
            Log.e(TAG, "Error logging notification", e)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Intentionally empty: we retain all notifications in the log even after removal.
    }
}
