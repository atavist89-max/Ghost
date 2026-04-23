package com.ghost.app.notification

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import com.ghost.app.utils.GhostPaths

/**
 * SQLite database for notification history logging.
 * Uses WAL mode for concurrent read/write between the background logger and PiP query engine.
 */
class NotificationDatabase(context: Context) : SQLiteOpenHelper(
    context,
    GhostPaths.NOTIFICATION_DB,
    null,
    DATABASE_VERSION
) {
    companion object {
        private const val TAG = "NotificationDB"
        private const val DATABASE_VERSION = 2
        const val TABLE_NOTIFICATIONS = "notifications"

        const val COL_ID = "id"
        const val COL_TIMESTAMP = "timestamp"
        const val COL_PACKAGE_NAME = "package_name"
        const val COL_APP_LABEL = "app_label"
        const val COL_TITLE = "title"
        const val COL_BODY = "body"
        const val COL_CATEGORY = "category"
        const val COL_PRIORITY = "priority"
        const val COL_IS_GROUP_SUMMARY = "is_group_summary"
        const val COL_DAY_OF_WEEK = "day_of_week"
        const val COL_HOUR_OF_DAY = "hour_of_day"
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(false)
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        db.rawQuery("PRAGMA journal_mode=WAL;", null).use { cursor ->
            if (cursor.moveToFirst()) {
                val mode = cursor.getString(0)
                Log.d(TAG, "Journal mode: $mode")
            }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_NOTIFICATIONS (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_TIMESTAMP INTEGER NOT NULL,
                $COL_PACKAGE_NAME TEXT,
                $COL_APP_LABEL TEXT,
                $COL_TITLE TEXT,
                $COL_BODY TEXT,
                $COL_CATEGORY TEXT,
                $COL_PRIORITY INTEGER,
                $COL_IS_GROUP_SUMMARY INTEGER,
                $COL_DAY_OF_WEEK INTEGER,
                $COL_HOUR_OF_DAY INTEGER
            )
            """.trimIndent()
        )

        db.execSQL("CREATE INDEX idx_timestamp_desc ON $TABLE_NOTIFICATIONS($COL_TIMESTAMP DESC)")
        db.execSQL("CREATE INDEX idx_package ON $TABLE_NOTIFICATIONS($COL_PACKAGE_NAME)")
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_unique_notification ON " +
            "$TABLE_NOTIFICATIONS(datetime($COL_TIMESTAMP/1000, 'unixepoch'), $COL_TITLE, $COL_BODY)"
        )
        Log.i(TAG, "Notification database created with WAL indexes")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL(
                "DELETE FROM $TABLE_NOTIFICATIONS WHERE $COL_ID NOT IN (" +
                "SELECT MIN($COL_ID) FROM $TABLE_NOTIFICATIONS " +
                "GROUP BY datetime($COL_TIMESTAMP/1000, 'unixepoch'), $COL_TITLE, $COL_BODY)"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS idx_unique_notification ON " +
                "$TABLE_NOTIFICATIONS(datetime($COL_TIMESTAMP/1000, 'unixepoch'), $COL_TITLE, $COL_BODY)"
            )
        }
    }
}
